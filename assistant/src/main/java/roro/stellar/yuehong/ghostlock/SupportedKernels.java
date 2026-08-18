package roro.stellar.yuehong.ghostlock;

/**
 * OTA-only compatibility marker.
 *
 * GhostLock no longer embeds kernel releases or per-kernel offsets. Runtime
 * support is established exclusively by a matching OTA-parsed offsets.json.
 */
public final class SupportedKernels {
    private SupportedKernels() {}
}
