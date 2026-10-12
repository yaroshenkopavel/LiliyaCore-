#!/usr/bin/env python3
"""Fail-closed supplemental validator for operator-collected ASF-H physical evidence.

This is not a substitute for the official self-hosted ARM64 workflow.
"""
import argparse
import hashlib
import json
import re
import sys
import zipfile

EXPECTED_SHA = "fa721c4dc571c9f7ffee67b1dabd15378832a4a5"
EXPECTED_ZIP_SHA256 = "e2f5635d61ea3583f0bc6bfbaf83bade6a28fcf372945a193accd427837a4662"
MODES = ("FULL_ONLY", "HIERARCHICAL")


def validate(path):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != EXPECTED_ZIP_SHA256:
        raise ValueError("physical evidence ZIP sha256 mismatch")
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError("corrupt ZIP member")
        names = archive.namelist()
        if len(names) != len(set(names)) or any(n.startswith("/") or ".." in n.split("/") for n in names):
            raise ValueError("duplicate or unsafe ZIP paths")
        manifest_paths = [n for n in names if n.endswith("manifest.json")]
        if len(manifest_paths) != 1:
            raise ValueError("expected exactly one manifest.json")
        manifest = json.loads(archive.read(manifest_paths[0]).decode("utf-8-sig"))
        return {"sha256": EXPECTED_ZIP_SHA256, "entries": len(names),
                "manifestFields": list(manifest.keys()), "paths": names}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=__import__("pathlib").Path)
    args = parser.parse_args()
    try:
        result = validate(args.archive)
        print(json.dumps({"verdict": "SUPPLEMENTAL_ARCHIVE_INTEGRITY_ONLY", **result}, indent=2))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"ASF603_V2_EVIDENCE_REJECTED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
