#!/usr/bin/env python3
"""Verify packaged native core without shell regex/pipefail ambiguity."""
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    names = set(apk.namelist())
    required = {"lib/arm64-v8a/libgojni.so", "lib/arm64-v8a/libbyedpi.so",
                "lib/arm64-v8a/libhev-socks5-tunnel.so"}
    missing = required - names
    if missing:
        raise SystemExit("Missing native libraries: " + ", ".join(sorted(missing)))
    if not any(name.startswith("classes") and name.endswith(".dex") for name in names):
        raise SystemExit("Missing DEX")
    for name in sorted(required):
        with apk.open(name) as native:
            if native.read(4) != b"\x7fELF":
                raise SystemExit("Invalid ELF: " + name)
    print("ARM64 sing-box + ByeDPI + HEV verified")
