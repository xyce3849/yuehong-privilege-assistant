//! Full-OTA extraction support: pull boot/xbl_config partitions from a
//! payload.bin or OTA ZIP using the payload-extract library.

use std::path::{Path, PathBuf};

use anyhow::Context;

pub const PAYLOAD_MAGIC: &[u8; 4] = b"CrAU";

/// Detect a payload.bin or OTA ZIP by magic bytes.
pub fn looks_like_payload(path: &str) -> bool {
    // payload-extract supports HTTP(S) payload sources directly.
    if path.starts_with("http://") || path.starts_with("https://") {
        return true;
    }
    let path = Path::new(path);
    let Ok(mut file) = std::fs::File::open(path) else {
        return false;
    };
    let mut head = [0u8; 4];
    use std::io::Read;
    if file.read(&mut head).unwrap_or(0) < 4 {
        return false;
    }
    head == *PAYLOAD_MAGIC || head == [0x50, 0x4B, 0x03, 0x04]
}

/// Read only the payload header and manifest (no data download), enough to
/// list partitions.  Works for local payload.bin / OTA ZIP files too.
pub fn open_payload_meta(input: &str) -> anyhow::Result<payload_extract::payload::PayloadView> {
    payload_extract::input::open(input, false, None)
        .with_context(|| format!("failed to read payload metadata '{input}'"))
}

/// Open a payload for extraction, downloading only the operation data of the
/// requested partitions.  An empty partition list would download the entire
/// payload, so callers must pass [`analysis_partition_names`]' output.
pub fn open_payload_for(
    input: &str,
    out_dir: &Path,
    partitions: &[String],
    on_progress: Option<payload_extract::input::ProgressCallback>,
) -> anyhow::Result<payload_extract::payload::PayloadView> {
    let opts = payload_extract::input::OpenOptions {
        temp_dir: Some(out_dir.to_path_buf()),
        download_progress: on_progress,
        ..Default::default()
    };
    payload_extract::input::open_for_extract_with(input, partitions, &opts)
        .with_context(|| format!("failed to open payload '{input}'"))
}

/// Partitions the analysis needs: boot (kernel image; BTF/kallsyms source)
/// plus xbl_config when present.  The GKI init_boot partition only carries
/// the generic ramdisk and is not an analysis input.
pub fn analysis_partition_names(
    payload: &payload_extract::payload::PayloadView,
) -> anyhow::Result<Vec<String>> {
    let available: Vec<String> = payload
        .partitions()
        .iter()
        .map(|p| p.partition_name.clone())
        .collect();
    if !available.iter().any(|n| n == "boot") {
        anyhow::bail!(
            "payload has no boot partition; init_boot only holds the GKI \
             ramdisk and cannot be analyzed"
        );
    }
    let mut want: Vec<String> = vec!["boot".to_string()];
    if available.iter().any(|n| n == "xbl_config") {
        want.push("xbl_config".to_string());
    }
    Ok(want)
}

/// Extract the requested partitions from an opened payload into `out_dir` and
/// return the produced files.
pub fn extract_partitions(
    payload: &payload_extract::payload::PayloadView,
    out_dir: &Path,
    partitions: &[String],
) -> anyhow::Result<Vec<PathBuf>> {
    std::fs::create_dir_all(out_dir)
        .with_context(|| format!("failed to create '{}'", out_dir.display()))?;
    let config = payload_extract::extract::ExtractConfig {
        verify_ops: false,
        threads: 0,
        quiet: true,
        source_dir: None,
        out_config: None,
        progress: None,
    };
    payload_extract::extract::extract_partitions(payload, out_dir, partitions, &config)
        .context("failed to extract partitions")?;
    let mut produced = Vec::new();
    for partition in partitions {
        let mut found: Vec<PathBuf> = Vec::new();
        for entry in std::fs::read_dir(out_dir).with_context(|| "read out dir")? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if name == *partition || name == format!("{partition}.img") {
                found.push(entry.path());
            }
        }
        match found.len() {
            0 => anyhow::bail!("partition '{partition}' not present in payload"),
            1 => produced.push(found.remove(0)),
            _ => anyhow::bail!("multiple outputs for partition '{partition}'"),
        }
    }
    Ok(produced)
}

/// Pick the analysis inputs from a full OTA payload: the boot partition
/// (kernel image; BTF/kallsyms source) plus xbl_config when present.  The
/// GKI init_boot partition only carries the generic ramdisk and is not an
/// analysis input.
pub fn extract_analysis_inputs(
    payload: &payload_extract::payload::PayloadView,
    out_dir: &Path,
) -> anyhow::Result<(PathBuf, Option<PathBuf>)> {
    let want = analysis_partition_names(payload)?;
    let produced = extract_partitions(payload, out_dir, &want)?;
    let boot_name = "boot".to_string();
    let boot_out = produced
        .iter()
        .find(|p| {
            p.file_name()
                .map(|n| {
                    n.to_string_lossy() == boot_name
                        || n.to_string_lossy() == format!("{boot_name}.img")
                })
                .unwrap_or(false)
        })
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("boot partition output not found"))?;
    let xbl_out = produced
        .iter()
        .find(|p| {
            p.file_name()
                .map(|n| n.to_string_lossy().starts_with("xbl_config"))
                .unwrap_or(false)
        })
        .cloned();
    Ok((boot_out, xbl_out))
}

#[cfg(test)]
mod tests {
    use super::open_payload_meta;
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::thread;
    use std::time::Duration;

    fn push_u16_le(output: &mut Vec<u8>, value: u16) {
        output.extend_from_slice(&value.to_le_bytes());
    }

    fn push_u32_le(output: &mut Vec<u8>, value: u32) {
        output.extend_from_slice(&value.to_le_bytes());
    }

    fn crc32(data: &[u8]) -> u32 {
        let mut crc = 0xffff_ffffu32;
        for byte in data {
            crc ^= *byte as u32;
            for _ in 0..8 {
                crc = (crc >> 1) ^ (0xedb8_8320u32 & (0u32.wrapping_sub(crc & 1)));
            }
        }
        !crc
    }

    fn minimal_payload() -> Vec<u8> {
        // DeltaArchiveManifest { block_size: 4096, partitions: [{ name: "boot" }] }
        let manifest = [
            0x18, 0x80, 0x20, 0x6a, 0x06, 0x0a, 0x04, b'b', b'o', b'o', b't',
        ];
        let mut payload = Vec::new();
        payload.extend_from_slice(b"CrAU");
        payload.extend_from_slice(&2u64.to_be_bytes());
        payload.extend_from_slice(&(manifest.len() as u64).to_be_bytes());
        payload.extend_from_slice(&0u32.to_be_bytes());
        payload.extend_from_slice(&manifest);
        payload
    }

    fn stored_ota_zip(payload: &[u8]) -> Vec<u8> {
        let name = b"payload.bin";
        let crc = crc32(payload);
        let size = payload.len() as u32;
        let mut zip = Vec::new();

        push_u32_le(&mut zip, 0x0403_4b50);
        push_u16_le(&mut zip, 20);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u32_le(&mut zip, crc);
        push_u32_le(&mut zip, size);
        push_u32_le(&mut zip, size);
        push_u16_le(&mut zip, name.len() as u16);
        push_u16_le(&mut zip, 0);
        zip.extend_from_slice(name);
        zip.extend_from_slice(payload);

        let central_directory_offset = zip.len() as u32;
        push_u32_le(&mut zip, 0x0201_4b50);
        push_u16_le(&mut zip, 20);
        push_u16_le(&mut zip, 20);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u32_le(&mut zip, crc);
        push_u32_le(&mut zip, size);
        push_u32_le(&mut zip, size);
        push_u16_le(&mut zip, name.len() as u16);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u32_le(&mut zip, 0);
        push_u32_le(&mut zip, 0);
        zip.extend_from_slice(name);

        let central_directory_size = zip.len() as u32 - central_directory_offset;
        push_u32_le(&mut zip, 0x0605_4b50);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 0);
        push_u16_le(&mut zip, 1);
        push_u16_le(&mut zip, 1);
        push_u32_le(&mut zip, central_directory_size);
        push_u32_le(&mut zip, central_directory_offset);
        push_u16_le(&mut zip, 0);
        zip
    }

    fn serve_ranges(listener: TcpListener, body: Arc<Vec<u8>>, stop: Arc<AtomicBool>) {
        listener.set_nonblocking(true).unwrap();
        while !stop.load(Ordering::Relaxed) {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(5));
                    continue;
                }
                Err(_) => break,
            };
            let mut request = [0u8; 8192];
            let read = stream.read(&mut request).unwrap_or(0);
            let request = String::from_utf8_lossy(&request[..read]);
            let range = request.lines().find_map(|line| {
                line.strip_prefix("Range: bytes=")
                    .or_else(|| line.strip_prefix("range: bytes="))
            });
            let (status, start, end) = if let Some(range) = range {
                let (start, end) = range.split_once('-').unwrap();
                let start = start.parse::<usize>().unwrap();
                let end = end
                    .parse::<usize>()
                    .unwrap_or(body.len().saturating_sub(1))
                    .min(body.len().saturating_sub(1));
                ("206 Partial Content", start, end)
            } else {
                ("200 OK", 0, body.len().saturating_sub(1))
            };
            let slice = &body[start..=end];
            let mut headers = format!(
                "HTTP/1.1 {status}\r\nAccept-Ranges: bytes\r\nContent-Length: {}\r\n",
                slice.len(),
            );
            if status.starts_with("206") {
                headers.push_str(&format!(
                    "Content-Range: bytes {start}-{end}/{}\r\n",
                    body.len(),
                ));
            }
            headers.push_str("Connection: close\r\n\r\n");
            stream.write_all(headers.as_bytes()).unwrap();
            stream.write_all(slice).unwrap();
        }
    }

    #[test]
    fn ota_url_reads_payload_metadata_through_http_ranges() {
        let body = Arc::new(stored_ota_zip(&minimal_payload()));
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let server_body = Arc::clone(&body);
        let server_stop = Arc::clone(&stop);
        let server = thread::spawn(move || serve_ranges(listener, server_body, server_stop));

        let result = open_payload_meta(&format!("http://{address}/ota.zip"));
        stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(address);
        server.join().unwrap();

        let payload = result.expect("remote OTA ZIP metadata should parse");
        let names: Vec<&str> = payload
            .partitions()
            .iter()
            .map(|partition| partition.partition_name.as_str())
            .collect();
        assert_eq!(names, ["boot"]);
    }
}
