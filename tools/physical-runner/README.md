# Physical ARM64 runner: Shizuku/rish bridge

This directory contains the bounded local bridge used when a GitHub Actions ARM64 runner is hosted inside Ubuntu/PRoot on the same Android phone that is the physical acceptance target.

The trust boundary is intentionally narrow:

- the bridge binds only `127.0.0.1`;
- requests require a bearer token stored in Termux private storage;
- there is no arbitrary-command endpoint;
- only fixed device facts, package reset, the two exact APK installs, and the two accepted instrumentation classes are exposed;
- uploaded APKs are size-bounded and SHA-256 verified before `pm install`;
- Android commands execute through Shizuku `rish` and therefore require a live Shizuku ADB service.

## One-time Termux setup

Prerequisites: Shizuku running in ADB mode, exported `rish`/`rish_shizuku.dex` installed in `$PREFIX/bin`, and `rish -c 'id'` returning `uid=2000(shell)`.

Create a private bridge token once:

```sh
umask 077
[ -s "$HOME/.liliya-physical-bridge-token" ] || \
  python -c 'import secrets; print(secrets.token_hex(32))' > "$HOME/.liliya-physical-bridge-token"
chmod 600 "$HOME/.liliya-physical-bridge-token"
```

Place `liliya_physical_rish_bridge.py` in Termux private storage and start it from native Termux, not from Ubuntu/PRoot:

```sh
termux-wake-lock 2>/dev/null || true
pkill -f liliya_physical_rish_bridge.py 2>/dev/null || true
nohup python "$HOME/liliya_physical_rish_bridge.py" \
  >"$HOME/liliya_physical_rish_bridge.log" 2>&1 &
```

The Ubuntu runner can verify the bridge without learning any arbitrary-shell capability:

```sh
TOKEN="$(cat /data/data/com.termux/files/home/.liliya-physical-bridge-token)"
curl --fail --silent --show-error \
  -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:18765/v1/probe
```

Do not expose port `18765` beyond loopback and do not move the token into shared storage.

## GitHub runner labels

The runner remains labelled:

- `self-hosted`
- `ARM64`
- `android-device`

The physical acceptance workflow selects the `rish-bridge` backend explicitly and still verifies exact source commit/tree, exact APK SHA-256/size, ARM64 ABI, non-emulator state, API floor, fixed instrumentation classes, and retained raw evidence.
