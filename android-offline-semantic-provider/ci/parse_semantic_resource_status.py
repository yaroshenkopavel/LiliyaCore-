#!/usr/bin/env python3
import json
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: parse_semantic_resource_status.py <status-log> <output-json>")

source = Path(sys.argv[1])
target = Path(sys.argv[2])
prefix = "INSTRUMENTATION_STATUS: semanticResource."
evidence = {}

for raw_line in source.read_text(encoding="utf-8", errors="replace").splitlines():
    line = raw_line.strip()
    if not line.startswith(prefix):
        continue
    key, separator, value = line[len(prefix):].partition("=")
    if separator:
        evidence[key] = value.strip()

required = {
    "primaryAbi",
    "abi",
    "fixtureBytes",
    "fixtureSha256",
    "fixtureAcceptance",
    "llamaRevision",
    "profileDimension",
    "loadLatencyMs",
    "processPssBeforeLoadBytes",
    "processPssAfterLoadBytes",
    "processPssLoadDeltaBytes",
    "nativeHeapLoadDeltaBytes",
    "warmShortQueryMedianMs",
    "paragraphLatencyMs",
    "repeatedEmbeddingCount",
    "flatScan1kMedianMs",
    "flatScan10kMedianMs",
    "flatIndex10kProcessPssDeltaBytes",
    "flatIndex10kRawVectorBytes",
    "flatIndexCandidateBound",
    "arm64ThresholdsApplied",
    "reloadCycle1LatencyMs",
    "reloadCycle2LatencyMs",
}
missing = sorted(required.difference(evidence))
if missing:
    raise SystemExit(f"missing semantic resource evidence keys: {missing}")

if evidence["arm64ThresholdsApplied"] != "false":
    raise SystemExit("translated x86_64 preflight must not claim ARM64 threshold evidence")

target.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
