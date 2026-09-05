#!/usr/bin/env python3
import json
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: parse_semantic_resource_status.py <input> <output-json>")

source = Path(sys.argv[1])
target = Path(sys.argv[2])
text = source.read_text(encoding="utf-8", errors="replace").strip()

if text.startswith("{"):
    evidence = json.loads(text)
else:
    prefix = "INSTRUMENTATION_STATUS: semanticResource."
    evidence = {}
    for raw_line in text.splitlines():
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
    "runtimeVersion",
    "profileDimension",
    "loadLatencyMs",
    "processPssBeforeLoadBytes",
    "processPssAfterLoadBytes",
    "processPssLoadDeltaBytes",
    "nativeHeapLoadDeltaBytes",
    "warmShortQueryMedianMs",
    "paragraphLatencyMs",
    "repeatedEmbeddingCount",
    "repeatedProcessPssBeforeBytes",
    "repeatedProcessPssAfterBytes",
    "repeatedProcessPssDeltaBytes",
    "repeatedNativeHeapBeforeBytes",
    "repeatedNativeHeapAfterBytes",
    "repeatedNativeHeapDeltaBytes",
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

primary_abi = str(evidence["primaryAbi"])
thresholds_applied = str(evidence["arm64ThresholdsApplied"]).lower()

if primary_abi == "arm64-v8a":
    if thresholds_applied != "true":
        raise SystemExit("ARM64 evidence must apply canonical ARM64 thresholds")
else:
    if thresholds_applied != "false":
        raise SystemExit("non-ARM64 preflight must not claim ARM64 threshold evidence")

target.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
