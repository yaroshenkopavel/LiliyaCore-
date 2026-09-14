# Physical ARM64 runner backend

This directory contains tooling for the strict physical First Working Liliya acceptance workflow.

## Shizuku/rish bridge

`liliya_physical_rish_bridge.py` is a localhost-only bridge intended to run in native Termux while the GitHub Actions ARM64 runner runs inside Ubuntu/PRoot on the same physical Android device.

Security and scope:
- binds only to `127.0.0.1`;
- requires a bearer token stored in Termux private storage;
- exposes only fixed device-fact, package-reset, exact APK-install, and accepted instrumentation operations;
- does not expose arbitrary shell/exec/command endpoints;
- uploaded APKs are size-bounded and SHA-256 verified before installation;
- production runtime code and product trust/authority/learning gates are not changed.

The workflow keeps the existing `adb` backend as an explicit alternative, while `rish-bridge` is the default for the phone-hosted self-hosted ARM64 runner.

Strict physical acceptance remains incomplete until a `workflow_dispatch` run executes the exact current canonical build on a real ARM64 Android device and the retained physical evidence artifact is GREEN.
