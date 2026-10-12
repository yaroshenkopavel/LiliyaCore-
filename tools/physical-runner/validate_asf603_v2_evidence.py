#!/usr/bin/env python3
"""Supplemental integrity and consistency checks for a specific operator USB/ADB V2 archive.

This intentionally does NOT certify the skipped official physical runner job.
"""
import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

EXPECTED_SHA = "fa721c4dc571c9f7ffee67b1dabd15378832a4a5"
EXPECTED_ZIP_SHA256 = "e2f5635d61ea3583f0bc6bfbaf83bade6a28fcf372945a193accd427837a4662"
MODES = ("FULL_ONLY", "HIERARCHICAL")


def check(condition, message):
    if not condition:
        raise ValueError(message)


def validate(path):
    check(hashlib.sha256(path.read_bytes()).hexdigest() == EXPECTED_ZIP_SHA256,
          "ZIP sha256 mismatch")
    with zipfile.ZipFile(path) as z:
        check(z.testzip() is None, "ZIP CRC failed")
        names = z.namelist()
        check(len(names) == len(set(names)) and
              not any(n.startswith("/") or ".." in n.split("/") for n in names),
              "duplicate/unsafe archive paths")
        manifest = json.loads(z.read("manifest.json").decode("utf-8-sig"))
        check(manifest["sourceCommit"] == EXPECTED_SHA, "manifest source SHA mismatch")
        check(manifest["abi"] == "arm64-v8a" and int(manifest["sdk"]) >= 29,
              "not Android ARM64")
        files = manifest["files"]
        check(set(names) == set(files) | {"manifest.json"}, "file manifest mismatch")
        for name, info in files.items():
            raw = z.read(name)
            check(len(raw) == info["size"] and
                  hashlib.sha256(raw).hexdigest() == info["sha256"],
                  "file hash/size mismatch: " + name)
        results = {}
        fingerprints = set()
        for mode in MODES:
            result = json.loads(z.read(f"result-{mode}.json").decode("utf-8-sig"))
            samples = json.loads(z.read(f"samples-{mode}.json").decode("utf-8-sig"))
            thermal = json.loads(z.read(f"thermal-samples-{mode}.json").decode("utf-8-sig"))
            summary = json.loads(z.read(f"asf-h-physical-{mode.lower().replace('_only','-only')}.json").decode("utf-8-sig"))
            log = z.read(f"instrument-{mode}.out.txt").decode("utf-8-sig")
            check(result["mode"] == mode and result["sourceCommit"] == EXPECTED_SHA,
                  "result source/mode mismatch: " + mode)
            check(result["instrumentationPassed"] is True and
                  "OK (1 test)" in log and re.search(r"INSTRUMENTATION_CODE:\s*-1", log),
                  "instrumentation mismatch: " + mode)
            check(len(samples) == result["memorySampleCount"] and len(samples) > 0,
                  "memory sample count mismatch: " + mode)
            check(max(x["pssKb"] for x in samples) == result["pssObservedMaxKb"] and
                  max(x["rssKb"] for x in samples) == result["rssObservedMaxKb"],
                  "memory observed maxima mismatch: " + mode)
            check(len(thermal) == result["thermalSampleCount"] and len(thermal) > 0 and
                  max(x["thermalStatus"] for x in thermal) == result["maxObservedThermalStatus"] == 0,
                  "thermal status mismatch: " + mode)
            check(summary["measuredRounds"] == 160 and
                  summary["completedStepsPerRound"] == summary["artifactCountPerRound"] == 3,
                  "synthetic workload mismatch: " + mode)
            check(summary["qualityFingerprint"] == result["qualityFingerprint"],
                  "quality fingerprint mismatch: " + mode)
            fingerprints.add(result["qualityFingerprint"])
            results[mode] = summary
        check(len(fingerprints) == 1, "modes differ in quality fingerprint")
        for key in ("modeledWallClockMillis", "modeledInferenceUnits",
                    "modeledContextBytes", "modeledRetrievalItems"):
            check(results["HIERARCHICAL"][key] < results["FULL_ONLY"][key],
                  "no modeled improvement: " + key)
        return {"verdict": "SUPPLEMENTAL_V2_VERIFIED_NOT_OFFICIAL_ARM64_GATE",
                "sourceCommit": EXPECTED_SHA, "archiveSha256": EXPECTED_ZIP_SHA256,
                "fileCount": len(files), "modes": list(MODES)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.archive), indent=2))
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as exc:
        print("ASF603_V2_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
