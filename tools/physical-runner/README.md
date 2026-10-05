# Physical ARM64 runner backend

This directory contains tooling for strict physical ARM64 acceptance and bounded physical evidence workflows.

## Shizuku/rish bridge

`liliya_physical_rish_bridge.py` is a localhost-only bridge intended to run in native Termux while the GitHub Actions ARM64 runner runs inside Ubuntu/PRoot on the same physical Android device.

Security and scope:
- binds only to `127.0.0.1`;
- requires a bearer token stored in Termux private storage;
- exposes only fixed device-fact, package-reset, exact APK-install, and allowlisted instrumentation operations;
- does not expose arbitrary shell/exec/command endpoints;
- uploaded APKs are size-bounded and SHA-256 verified before installation;
- APK installation is staged through `/data/local/tmp/liliya-physical-acceptance` so Android `system_server` can read the package bytes;
- production runtime code and product trust/authority/learning gates are not changed.

The accepted First Working Liliya physical workflow uses the fixed app/test package endpoints plus the provisioning and cold-start instrumentation endpoints. Exact-current-main workflow_dispatch run `34875198763` completed GREEN on the physical ARM64 target and closed hardware acceptance issue #258.

The bridge also contains a separate fixed semantic-test-host package pair and one allowlisted Qwen3 candidate instrumentation endpoint. That endpoint exists only to support bounded external model evidence for #260/#294; it does not select, install, activate, or accept a production model. The candidate test still fails closed on the exact Qwen3 artifact identity and the accepted `MODEL_DEFAULT_CHAT_TEMPLATE` path.

For post-RC governed-learning work, the bridge also exposes one fixed `governed-learning-semantic-rebuild` instrumentation endpoint. It can run only `AndroidHeartRuntimeColdStartInstrumentedTest#product_runtime_assembly_routes_conversation_evidence_to_explicit_governed_learning` in the semantic test package. It does not expose arbitrary execution, does not enable First Working learning, and is not acceptance evidence by itself; #305 physical evidence exists only after an explicit physical workflow dispatch succeeds on the ARM64 device.

Large candidate-model execution is intentionally not automatic. Any workflow that invokes the Qwen3 candidate path must require an explicit confirmation because the exact candidate download is about 1.28 GB and may otherwise consume a metered mobile connection.
