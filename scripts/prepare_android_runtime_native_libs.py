#!/usr/bin/env python3
from __future__ import annotations

import argparse
import glob
import shutil
import re
from pathlib import Path


ANDROID_ABIS = ("arm64-v8a", "x86_64")
NATIVE_EXECUTABLES = {
    "bin/proot": "libopencode_android_proot.so",
    "libexec/proot/loader": "libopencode_android_proot_loader.so",
    "libexec/proot/loader32": "libopencode_android_proot_loader32.so",
}
# Optional libraries: copied when the pinned packages provide them.
RUNTIME_LIBRARIES = {
    "libandroid-shmem.so": "libandroid-shmem.so",
    "libc++_shared.so": "libc++_shared.so",
}
# Required libraries, matched by glob because Termux ships them under their full version
# (libtalloc.so.2.4.3, libtalloc.so.2.5.0, ...) which changes with every lock refresh. A stale
# hardcoded name used to make this step silently skip the file - the APK then contained a proot
# binary whose DT_NEEDED (patched below to libtalloc.so) resolved to nothing, and the packaged
# app died with "library libtalloc.so not found" on the very first proot exec on device.
REQUIRED_RUNTIME_LIB_GLOBS = {
    "libtalloc.so*": "libtalloc.so",
}
NATIVE_EXECUTABLE_SEARCH_DIRS = ("bin", "libexec")


def native_executable_name(relative_path: str) -> str:
    existing = NATIVE_EXECUTABLES.get(relative_path)
    if existing:
        return existing
    safe = re.sub(r"[^0-9A-Za-z_]+", "_", relative_path.replace("\\", "/"))
    safe = safe.strip("_") or "command"
    return f"libopencode_exec_{safe}.so"


def patch_needed(path: Path, old_name: str, new_name: str) -> None:
    if not path.is_file():
        return
    old = old_name.encode("utf-8") + b"\0"
    new = new_name.encode("utf-8") + b"\0"
    if len(new) > len(old):
        raise ValueError(f"replacement {new_name!r} is longer than {old_name!r}")
    payload = path.read_bytes()
    if old not in payload:
        return
    payload = payload.replace(old, new + (b"\0" * (len(old) - len(new))))
    path.write_bytes(payload)


def copy_abi(linux_assets_dir: Path, output_dir: Path, abi: str) -> None:
    prefix_dir = linux_assets_dir / "opencode-runtime" / abi / "prefix"
    abi_output = output_dir / abi
    abi_output.mkdir(parents=True, exist_ok=True)
    for source_relative, destination_name in sorted(NATIVE_EXECUTABLES.items()):
        source = prefix_dir / source_relative
        if not source.is_file():
            raise FileNotFoundError(f"Required Android runtime executable missing: {source}")
        destination = abi_output / destination_name
        shutil.copy2(source, destination)
        destination.chmod(0o755)
    lib_dir = prefix_dir / "lib"
    for source_name, destination_name in sorted(REQUIRED_RUNTIME_LIB_GLOBS.items()):
        matches = sorted(glob.glob(str(lib_dir / source_name)))
        if not matches:
            raise FileNotFoundError(
                f"Required Android runtime library missing: expected {lib_dir / source_name}"
            )
        destination = abi_output / destination_name
        shutil.copy2(matches[0], destination)
        destination.chmod(0o755)
    for source_name, destination_name in sorted(RUNTIME_LIBRARIES.items()):
        source = lib_dir / source_name
        if source.is_file():
            destination = abi_output / destination_name
            shutil.copy2(source, destination)
            destination.chmod(0o755)
    patch_needed(abi_output / "libopencode_android_proot.so", "libtalloc.so.2", "libtalloc.so")


def prepare_native_libs(linux_assets_dir: Path, output_dir: Path) -> None:
    if output_dir.exists():
        for item in output_dir.rglob("*"):
            if item.is_file():
                item.unlink()
        for item in sorted((p for p in output_dir.rglob("*") if p.is_dir()), reverse=True):
            item.rmdir()
    output_dir.mkdir(parents=True, exist_ok=True)
    for abi in ANDROID_ABIS:
        copy_abi(linux_assets_dir, output_dir, abi)


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Android-packaged native launcher libraries")
    parser.add_argument("--linux-assets-dir", required=True, help="Generated AndCode runtime assets directory")
    parser.add_argument("--output-dir", required=True, help="Generated jniLibs output directory")
    args = parser.parse_args()
    prepare_native_libs(
        linux_assets_dir=Path(args.linux_assets_dir).expanduser().resolve(),
        output_dir=Path(args.output_dir).expanduser().resolve(),
    )


if __name__ == "__main__":
    main()
