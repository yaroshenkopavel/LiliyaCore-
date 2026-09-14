#!/usr/bin/env python3
"""Local-only bridge from an Ubuntu/PRoot GitHub runner to Android shell via Shizuku rish.

Security properties:
- binds only 127.0.0.1;
- requires a bearer token stored in Termux private storage;
- exposes only fixed device/provisioning/instrumentation operations;
- never accepts an arbitrary shell command or arbitrary destination path;
- uploaded APK bytes are size-bounded and SHA-256 verified before install.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HOST = "127.0.0.1"
PORT = int(os.environ.get("LILIYA_PHYSICAL_BRIDGE_PORT", "18765"))
TERMUX_HOME = Path(os.environ.get("HOME", "/data/data/com.termux/files/home"))
RISH = Path(os.environ.get("LILIYA_RISH_PATH", "/data/data/com.termux/files/usr/bin/rish"))
TOKEN_FILE = Path(
    os.environ.get(
        "LILIYA_PHYSICAL_BRIDGE_TOKEN_FILE",
        str(TERMUX_HOME / ".liliya-physical-bridge-token"),
    )
)
STAGING_DIR = Path(
    os.environ.get(
        "LILIYA_PHYSICAL_BRIDGE_STAGING_DIR",
        "/storage/emulated/0/Download/LiliyaPhysicalAcceptance",
    )
)
DEVICE_INSTALL_DIR = "/data/local/tmp/liliya-physical-acceptance"
MAX_APK_BYTES = 512 * 1024 * 1024
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

APP_PACKAGE = "pro.liliya.app"
TEST_PACKAGE = "pro.liliya.app.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
PROVISIONING_TEST = "pro.liliya.app.PhysicalProductAuthProvisioningToChatInstrumentedTest"
COLD_START_TEST = "pro.liliya.app.DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest"

UPLOADS = {
    "/v1/install/app": ("liliya-app-debug.apk", APP_PACKAGE),
    "/v1/install/test": ("liliya-app-debug-androidTest.apk", TEST_PACKAGE),
}


def load_token() -> str:
    try:
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise SystemExit(f"cannot read bridge token file {TOKEN_FILE}: {exc}") from exc
    if len(token) < 32:
        raise SystemExit("bridge token must contain at least 32 characters")
    return token


def run_rish(command: str, timeout: int = 180) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(RISH), "-c", command],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )


def fixed_probe() -> dict[str, object]:
    commands = {
        "shellIdentity": "id",
        "model": "getprop ro.product.model",
        "primaryAbi": "getprop ro.product.cpu.abi",
        "sdk": "getprop ro.build.version.sdk",
        "qemu": "getprop ro.kernel.qemu",
        "appPath": f"pm path {APP_PACKAGE}",
        "testPath": f"pm path {TEST_PACKAGE}",
    }
    result: dict[str, object] = {"backend": "shizuku-rish"}
    for key, command in commands.items():
        completed = run_rish(command, timeout=30)
        result[key] = {
            "exit": completed.returncode,
            "output": completed.stdout.strip(),
        }
    return result


TOKEN = load_token()


class Handler(BaseHTTPRequestHandler):
    server_version = "LiliyaPhysicalRishBridge/1"

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("bridge: " + (fmt % args) + "\n")

    def _authorized(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def _require_auth(self) -> bool:
        if self._authorized():
            return True
        self.send_response(401)
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def _json(self, status: int, value: object) -> None:
        body = (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _text(self, status: int, value: str) -> None:
        body = value.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if not self._require_auth():
            return
        path = urlsplit(self.path).path
        if path == "/v1/probe":
            self._json(200, fixed_probe())
            return
        self._json(404, {"error": "unknown endpoint"})

    def do_POST(self) -> None:  # noqa: N802
        if not self._require_auth():
            return
        path = urlsplit(self.path).path

        if path == "/v1/reset-packages":
            outputs = []
            for package in (TEST_PACKAGE, APP_PACKAGE):
                completed = run_rish(f"pm uninstall {package}", timeout=60)
                outputs.append(
                    {
                        "package": package,
                        "exit": completed.returncode,
                        "output": completed.stdout.strip(),
                    }
                )
            self._json(200, {"operation": "reset-packages", "results": outputs})
            return

        if path in UPLOADS:
            self._install_upload(path)
            return

        if path == "/v1/instrument/provisioning":
            self._instrument(PROVISIONING_TEST)
            return

        if path == "/v1/instrument/cold-start":
            self._instrument(COLD_START_TEST)
            return

        self._json(404, {"error": "unknown endpoint"})

    def _install_upload(self, path: str) -> None:
        filename, package = UPLOADS[path]
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self._json(411, {"error": "valid Content-Length required"})
            return
        if length <= 0 or length > MAX_APK_BYTES:
            self._json(413, {"error": "APK size outside accepted range"})
            return

        expected_sha = self.headers.get("X-Liliya-Sha256", "").lower()
        if not SHA256_RE.fullmatch(expected_sha):
            self._json(400, {"error": "valid X-Liliya-Sha256 required"})
            return

        STAGING_DIR.mkdir(parents=True, exist_ok=True)
        destination = STAGING_DIR / filename
        temporary = STAGING_DIR / f".{filename}.upload"
        digest = hashlib.sha256()
        remaining = length

        try:
            with temporary.open("wb") as stream:
                while remaining:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise OSError("request body ended before Content-Length")
                    stream.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
            actual_sha = digest.hexdigest()
            if actual_sha != expected_sha:
                temporary.unlink(missing_ok=True)
                self._json(
                    400,
                    {
                        "error": "APK sha256 mismatch",
                        "expected": expected_sha,
                        "actual": actual_sha,
                    },
                )
                return
            os.replace(temporary, destination)
        except OSError as exc:
            temporary.unlink(missing_ok=True)
            self._json(500, {"error": f"upload staging failed: {exc}"})
            return

        device_destination = f"{DEVICE_INSTALL_DIR}/{filename}"
        install_command = (
            f"mkdir -p '{DEVICE_INSTALL_DIR}' && "
            f"rm -f '{device_destination}' && "
            f"cp '{destination}' '{device_destination}' && "
            f"chmod 0644 '{device_destination}' && "
            f"pm install -r -t '{device_destination}'; "
            "rc=$?; "
            f"rm -f '{device_destination}'; "
            "exit $rc"
        )
        completed = run_rish(install_command, timeout=180)
        package_status = run_rish(f"pm path {package}", timeout=30)
        response = {
            "operation": "install",
            "package": package,
            "file": filename,
            "sha256": actual_sha,
            "size": length,
            "installExit": completed.returncode,
            "installOutput": completed.stdout.strip(),
            "packagePathExit": package_status.returncode,
            "packagePath": package_status.stdout.strip(),
        }
        ok = completed.returncode == 0 and package_status.returncode == 0 and package_status.stdout.strip().startswith("package:")
        self._json(200 if ok else 500, response)

    def _instrument(self, test_class: str) -> None:
        command = (
            "am instrument -w -r "
            f"-e class {test_class} "
            f"{TEST_PACKAGE}/{RUNNER}"
        )
        completed = run_rish(command, timeout=900)
        output = completed.stdout
        ok = (
            completed.returncode == 0
            and "INSTRUMENTATION_CODE: -1" in output
            and "FAILURES!!!" not in output
            and "INSTRUMENTATION_FAILED" not in output
            and "Process crashed" not in output
        )
        self._text(200 if ok else 500, output)


def main() -> None:
    if not RISH.is_file():
        raise SystemExit(f"rish not found: {RISH}")
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Liliya physical rish bridge listening on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
