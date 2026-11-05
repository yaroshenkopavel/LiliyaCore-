# ASF-H — manual physical ARM64 measurement record (2026-10-13)

**Acceptance classification:** physical synthetic functional and resource measurement **PASS**; **formal GitHub Actions physical job success and production budget approval NOT established**.

## Identity and provenance

- Main source revision used to build and install isolated APKs: `3e35138e1033836f66eec516b4a26562120b2c45`.
- Device: physical Android arm64-v8a, SDK 34, model 23030RAC7Y; connected via USB ADB.
- Exact app packages attested by ADB SHA-256 against the GitHub bundle before execution: `pro.liliya.app.asfh`, `pro.liliya.app.asfh.test`.
- GitHub archive: `asf-h-physical-test-bundle-3e35138e1033836f66eec516b4a26562120b2c45.zip`.
- Source of measurements: local Shizuku/rish metrics bridge; generated evidence class `asf-h-android-physical-resource-evidence-v1`.
- **Original raw report:** `ASF-H-physical-final.json`, 42,349 bytes, SHA-256 `4417b7257bc23a4f4940108c82d3fed60ca8fb412d79da081b02a6819c9b2c22`. Preserved on owner's laptop at `C:\LiliyaServer\evidence\asf-h\ASF-H-physical-final.json` and supplied as a conversation attachment. This repository review is NOT a substitute for that original file.
- Test interval according to the report: 2026-10-13 12:01:08–12:01:36 UTC (~27,969 ms).
- Overall report `instrumentationPassed=true`, verdict `MEASURED_NO_PRODUCT_BUDGET_APPLIED`.

## Measured comparison

| Metric | FULL_ONLY | HIERARCHICAL |
|---|---:|---:|
| Instrumentation | PASS (exit 0, no timeout) | PASS (exit 0, no timeout) |
| Warmup / measured rounds | 20 / 160 | 20 / 160 |
| Actual loop elapsed | 2,169 ms | 2,059 ms |
| Peak PSS | 55,976 KiB | 56,039 KiB |
| Peak RSS | 155,952 KiB | 156,824 KiB |
| Valid memory readings | 9 of 10 | 8 of 9 |
| HAL CPU before → after | 45.925 → 46.862 °C | 45.950 → 50.022 °C |
| HAL battery before → after | 35.2 → 35.3 °C | 35.3 → 35.3 °C |
| Completed steps / artifacts per round | 3 / 3 | 3 / 3 |
| Agents started / artifacts | 480 / 480 | 480 / 480 |
| Modeled inference units | 480,000 | 204,000 |
| Modeled wall-clock | 57,600 ms | 26,880 ms |

- Identical `qualityFingerprint` for both modes, for the **bounded synthetic workload only**.
- Measured loop time: HIERARCHICAL faster by 110 ms (~5.1%) **in this one comparison**; this is not a statistically established speedup.
- Peak PSS higher by 63 KiB and peak RSS higher by 872 KiB in HIERARCHICAL. **No physical RAM saving was demonstrated**.
- Temperature snapshots report Android Thermal Status 0 before/after; no continuous high-frequency thermal peak measurement occurred during the ~2 s workloads.
- First memory sample in each mode reported `No process found`, not a valid zero-memory sample.
- Modeled resource savings are workload accounting, **not real Qwen3 inference throughput**.

## Scope and formal closure

This report supports successful execution and resource observation of ASF-H's synthetic test on physical ARM64. It does **not** claim production memory budgets, energy savings, real-model inference improvements, repeated-run statistical confidence, or official GitHub Actions physical job success.

For official closure, repository owners must decide whether the existing workflow contract requires a separate protected-main GitHub Actions physical-success run or permits independently collected and reviewed manual physical evidence. Do not relabel a `skipped` or `failed` physical job as successful.

The working `pro.liliya.app` application was not reinstalled, uninstalled, or data-reset for this experiment.
