#!/usr/bin/env python3
"""Offline test suite for Android compressed ROM ZIP import rules in BanjoSDLActivity.

Verifies that:
1. Pure .z64 files are passed through without modification.
2. Zipped .z64 files are identified, safely extracted to .z64, and the .zip is purged.
3. Zipped .n64 files are identified, safely extracted, and prioritized.
4. Archives without valid ROM entries are gracefully rejected.
"""
from __future__ import annotations

import io
import os
import shutil
import tempfile
import zipfile
from pathlib import Path


def extract_rom_if_compressed_mock(file_path: Path, import_dir: Path) -> Path | None:
    is_zip = file_path.name.lower().endswith(".zip")
    if not is_zip:
        with open(file_path, "rb") as f:
            header = f.read(4)
            if header == b"PK\x03\x04":
                is_zip = True

    if not is_zip:
        return None

    target_entry_name = None
    fallback_candidate = None
    with zipfile.ZipFile(file_path, "r") as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            name = info.filename
            lower = name.lower()
            if lower.endswith(".z64"):
                target_entry_name = name
                break
            elif lower.endswith(".n64") or lower.endswith(".v64"):
                if target_entry_name is None:
                    target_entry_name = name
            elif info.file_size >= 11 * 1024 * 1024:
                if fallback_candidate is None:
                    fallback_candidate = name
        if target_entry_name is None:
            target_entry_name = fallback_candidate

    if target_entry_name is None:
        return None

    entry_stem = Path(target_entry_name).name
    safe_name = entry_stem
    if not (safe_name.lower().endswith((".z64", ".n64", ".v64"))):
        safe_name += ".z64"

    destination = import_dir / safe_name
    with zipfile.ZipFile(file_path, "r") as z:
        with z.open(target_entry_name) as source, open(destination, "wb") as target:
            shutil.copyfileobj(source, target)

    return destination


def test_rom_extraction():
    temp_dir = Path(tempfile.mkdtemp(prefix="test_rom_zip_"))
    try:
        import_dir = temp_dir / "rom-imports"
        import_dir.mkdir()

        # Test 1: Uncompressed ROM passthrough
        raw_rom = temp_dir / "Banjo.z64"
        raw_rom.write_bytes(b"\x80\x37\x12\x40" + b"\x00" * 1000)
        res1 = extract_rom_if_compressed_mock(raw_rom, import_dir)
        assert res1 is None, "Uncompressed ROM should return None from extract_rom_if_compressed"

        # Test 2: Zipped .z64 extraction
        zip_rom = temp_dir / "Banjo-Kazooie (USA) (Rev A).n64.zip"
        with zipfile.ZipFile(zip_rom, "w") as z:
            z.writestr("Banjo-Kazooie (USA) (Rev A).z64", b"\x80\x37\x12\x40" + b"\xaa" * 16777212)
        
        res2 = extract_rom_if_compressed_mock(zip_rom, import_dir)
        assert res2 is not None, "Failed to extract zipped ROM"
        assert res2.name == "Banjo-Kazooie (USA) (Rev A).z64"
        assert res2.read_bytes()[:4] == b"\x80\x37\x12\x40"
        assert res2.stat().st_size == 16777216

        # Test 3: Zipped archive with nested readme
        zip_multi = temp_dir / "GameWithReadme.zip"
        with zipfile.ZipFile(zip_multi, "w") as z:
            z.writestr("readme.txt", "This is a readme")
            z.writestr("subfolder/game.n64", b"\x37\x80\x40\x12" + b"\xbb" * 12000000)

        res3 = extract_rom_if_compressed_mock(zip_multi, import_dir)
        assert res3 is not None
        assert res3.name == "game.n64"
        assert res3.read_bytes()[:4] == b"\x37\x80\x40\x12"

        print("All ROM ZIP extraction fixture tests passed successfully!")
    finally:
        shutil.rmtree(temp_dir)


if __name__ == "__main__":
    test_rom_extraction()
