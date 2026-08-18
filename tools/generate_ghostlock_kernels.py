#!/usr/bin/env python3
"""Generate the OTA-only GhostLock compatibility marker.

Per-kernel offset tables are intentionally never emitted. Runtime offsets must
come from an offsets.json produced by parsing the current device full OTA.
"""
from __future__ import annotations

import argparse
from pathlib import Path


SOURCE = """package in.hridayan.ashell.ghostlock;

/**
 * OTA-only compatibility marker.
 *
 * GhostLock no longer embeds kernel releases or per-kernel offsets. Runtime
 * support is established exclusively by a matching OTA-parsed offsets.json.
 */
public final class SupportedKernels {
    private SupportedKernels() {}
}
"""


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    # Retained for command compatibility; OTA-only generation never reads it.
    parser.add_argument("--vendor-root", type=Path, default=project_root / "third_party/ghostlock")
    parser.add_argument(
        "--output",
        type=Path,
        default=project_root / "assistant/src/main/java/in/hridayan/ashell/ghostlock/SupportedKernels.java",
    )
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(SOURCE, encoding="utf-8", newline="\n")
    print(f"GENERATED_KERNELS=0 OTA_ONLY=true OUTPUT={args.output.resolve()}")


if __name__ == "__main__":
    main()