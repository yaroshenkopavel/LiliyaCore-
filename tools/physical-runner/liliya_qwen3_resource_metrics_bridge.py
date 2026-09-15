#!/usr/bin/env python3
"""Local-only fixed-purpose Qwen3 resource evidence bridge.

This bridge intentionally does NOT expose arbitrary shell/command/exec operations.
It runs exactly one bounded instrumentation class and fixed Android diagnostics via
Shizuku rish while that instrumentation is active.

Security properties:
- binds only 127.0.0.1;
- requires the existing Termux-private bearer token;
- accepts no command/path/package/test-class input from the caller;
- runs only fixed diagnostics and one fixed instrumentation class;
- returns raw diagnostic evidence plus conservative parsed metrics;
- never returns the bearer token.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HOST = "127.0.0.1"
PORT = int(os.environ.get("LILIYA_QWEN3_METRICS_BRIDGE_PORT", "18766"))
TERMUX_HOME = Path(os.environ.get("HOME", "/data/data/com.termux/files/home"))
RISH = Path(os.environ.get("LILIYA_RISH_PATH", "/data/data/com.termux/files/usr/bin/rish"))
TOKEN_FILE = Path(
    os.environ.get(
        "LILIYA_PHYSICAL_BRIDGE_TOKEN_FILE",
        str(TERMUX_HOME / ".liliya-physical-bridge-token"),
    )
)

SEMANTIC_APP_PACKAGE = "pro.liliya.android.semanticprovider"
SEMANTIC_TEST_PACKAGE = "pro.liliya.android.semanticprovider.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
QWEN3_RUSSIAN_CONTEXT_TEST = (
    "pro.liliya.android.semanticprovider."
    "Qwen3RussianQualityContextCandidateInstrumentedTest"
)

SAMPLE_INTERVAL_SECONDS = 2.0
THERMAL_SAMPLE_EVERY = 5
INSTRUMENTATION_TIMEOUT_SECONDS = 3600
MAX_RAW_SAMPLE_CHARS = 48_000

TOTAL_PSS_RE = re.compile(r"TOTAL PSS:\s*(\d+)")
TOTAL_RSS_RE = re.compile(r"TOTAL RSS:\s*(\d+)")
KEY_VALUE_RE = re.compile(r"^\s*([^:]+):\s*(.*?)\s*$")


def load_token() -> str:
    try:
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise SystemExit(f"cannot read bridge token file {TOKEN_FILE}: {exc}") from exc
    if len(token) < 32:
        raise SystemExit("bridge token must contain at least 32 characters")
    return token


def run_rish(command: str, timeout: int = 60) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(RISH), "-c", command],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )


def fixed_device_probe() -> dict[str, object]:
    commands = {
        "shellIdentity": "id",
        "model": "getprop ro.product.model",
        "primaryAbi": "getprop ro.product.cpu.abi",
        "sdk": "getprop ro.build.version.sdk",
        "qemu": "getprop ro.kernel.qemu",
        "semanticAppPath": f"pm path {SEMANTIC_APP_PACKAGE}",
        "semanticTestPath": f"pm path {SEMANTIC_TEST_PACKAGE}",
    }
    result: dict[str, object] = {
        "evidenceClass": "qwen3-resource-metrics-bridge-probe-v1",
        "backend": "shizuku-rish",
    }
    for key, command in commands.items():
        completed = run_rish(command, timeout=30)
        result[key] = {
            "exit": completed.returncode,
            "output": completed.stdout.strip(),
        }
    return result


def parse_battery(raw: str) -> dict[str, object]:
    wanted = {
        "level",
        "scale",
        "status",
        "plugged",
        "temperature",
        "voltage",
        "present",
        "AC powered",
        "USB powered",
        "Wireless powered",
        "Max charging current",
        "Max charging voltage",
        "Charge counter",
    }
    parsed: dict[str, object] = {}
    for line in raw.splitlines():
        match = KEY_VALUE_RE.match(line)
        if not match:
            continue
        key, value = match.group(1).strip(), match.group(2).strip()
        if key not in wanted:
            continue
        if re.fullmatch(r"-?\d+", value):
            parsed[key] = int(value)
        elif value.lower() in {"true", "false"}:
            parsed[key] = value.lower() == "true"
        else:
            parsed[key] = value
    return parsed


def parse_meminfo(raw: str) -> dict[str, int | None]:
    pss = TOTAL_PSS_RE.search(raw)
    rss = TOTAL_RSS_RE.search(raw)
    return {
        "totalPssKb": int(pss.group(1)) if pss else None,
        "totalRssKb": int(rss.group(1)) if rss else None,
    }


def bounded_raw(value: str) -> str:
    if len(value) <= MAX_RAW_SAMPLE_CHARS:
        return value
    return value[:MAX_RAW_SAMPLE_CHARS] + "\n<truncated>\n"


def diagnostic(command: str, timeout: int = 30) -> dict[str, object]:
    completed = run_rish(command, timeout=timeout)
    return {
        "exit": completed.returncode,
        "output": bounded_raw(completed.stdout.strip()),
    }


def storage_snapshot() -> dict[str, object]:
    # The semantic host is a debug/test host, so run-as is the least-privilege way
    # to observe its own app-private storage without exposing an arbitrary path API.
    return diagnostic(
        f"run-as {SEMANTIC_APP_PACKAGE} sh -c '"
        "echo __ROOT_KB__; du -sk . 2>/dev/null || true; "
        "echo __FILES_KB__; du -sk files 2>/dev/null || true; "
        "echo __CACHE_KB__; du -sk cache 2>/dev/null || true; "
        "echo __DF__; df -k . 2>/dev/null || true'",
        timeout=60,
    )


def take_sample(index: int, include_thermal: bool) -> dict[str, object]:
    captured_at = time.time()
    mem = diagnostic(f"dumpsys meminfo {SEMANTIC_APP_PACKAGE}", timeout=30)
    sample: dict[str, object] = {
        "index": index,
        "epochSeconds": captured_at,
        "memory": {
            **parse_meminfo(str(mem["output"])),
            "exit": mem["exit"],
            "raw": mem["output"],
        },
    }
    if include_thermal:
        thermal = diagnostic("dumpsys thermalservice", timeout=30)
        sample["thermal"] = {
            "exit": thermal["exit"],
            "raw": thermal["output"],
        }
    return sample


def peak_metric(samples: list[dict[str, object]], key: str) -> int | None:
    values: list[int] = []
    for sample in samples:
        memory = sample.get("memory")
        if not isinstance(memory, dict):
            continue
        value = memory.get(key)
        if isinstance(value, int):
            values.append(value)
    return max(values) if values else None


def run_instrumentation_with_metrics() -> dict[str, object]:
    started_epoch = time.time()
    battery_before_raw = diagnostic("dumpsys battery", timeout=30)
    thermal_before = diagnostic("dumpsys thermalservice", timeout=30)
    storage_before = storage_snapshot()

    command = (
        "am instrument -w -r "
        f"-e class {QWEN3_RUSSIAN_CONTEXT_TEST} "
        f"{SEMANTIC_TEST_PACKAGE}/{RUNNER}"
    )

    process = subprocess.Popen(
        [str(RISH), "-c", command],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )

    samples: list[dict[str, object]] = []
    timed_out = False
    sample_index = 0
    deadline = time.monotonic() + INSTRUMENTATION_TIMEOUT_SECONDS

    while process.poll() is None:
        if time.monotonic() >= deadline:
            timed_out = True
            process.kill()
            break
        samples.append(
            take_sample(
                sample_index,
                include_thermal=(sample_index % THERMAL_SAMPLE_EVERY == 0),
            )
        )
        sample_index += 1
        time.sleep(SAMPLE_INTERVAL_SECONDS)

    try:
        output, _ = process.communicate(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()
        output, _ = process.communicate()
        timed_out = True

    ended_epoch = time.time()
    battery_after_raw = diagnostic("dumpsys battery", timeout=30)
    thermal_after = diagnostic("dumpsys thermalservice", timeout=30)
    storage_after = storage_snapshot()

    instrumentation_ok = (
        not timed_out
        and process.returncode == 0
        and "INSTRUMENTATION_CODE: -1" in output
        and "FAILURES!!!" not in output
        and "INSTRUMENTATION_FAILED" not in output
        and "Process crashed" not in output
    )

    result = {
        "evidenceClass": "qwen3-russian-context-resource-metrics-v1",
        "bridgeBackend": "shizuku-rish",
        "semanticAppPackage": SEMANTIC_APP_PACKAGE,
        "semanticTestPackage": SEMANTIC_TEST_PACKAGE,
        "testClass": QWEN3_RUSSIAN_CONTEXT_TEST,
        "sampleIntervalSeconds": SAMPLE_INTERVAL_SECONDS,
        "startedEpochSeconds": started_epoch,
        "endedEpochSeconds": ended_epoch,
        "elapsedMillis": int(round((ended_epoch - started_epoch) * 1000)),
        "instrumentationExit": process.returncode,
        "instrumentationTimedOut": timed_out,
        "instrumentationPassed": instrumentation_ok,
        "instrumentationOutput": bounded_raw(output),
        "peakTotalPssKb": peak_metric(samples, "totalPssKb"),
        "peakTotalRssKb": peak_metric(samples, "totalRssKb"),
        "sampleCount": len(samples),
        "batteryBefore": {
            "parsed": parse_battery(str(battery_before_raw["output"])),
            "raw": battery_before_raw,
        },
        "batteryAfter": {
            "parsed": parse_battery(str(battery_after_raw["output"])),
            "raw": battery_after_raw,
        },
        "thermalBefore": thermal_before,
        "thermalAfter": thermal_after,
        "storageBefore": storage_before,
        "storageAfter": storage_after,
        "samples": samples,
    }
    return result


TOKEN = load_token()
MEASUREMENT_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server_version = "LiliyaQwen3ResourceMetricsBridge/1"

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("qwen3-metrics-bridge: " + (fmt % args) + "\n")

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

    def do_GET(self) -> None:  # noqa: N802
        if not self._require_auth():
            return
        path = urlsplit(self.path).path
        if path == "/v1/probe":
            self._json(200, fixed_device_probe())
            return
        self._json(404, {"error": "unknown endpoint"})

    def do_POST(self) -> None:  # noqa: N802
        if not self._require_auth():
            return
        path = urlsplit(self.path).path
        if path != "/v1/instrument/qwen3-russian-context-resources":
            self._json(404, {"error": "unknown endpoint"})
            return

        if not MEASUREMENT_LOCK.acquire(blocking=False):
            self._json(409, {"error": "resource measurement already running"})
            return
        try:
            result = run_instrumentation_with_metrics()
            self._json(200 if result["instrumentationPassed"] else 500, result)
        except subprocess.TimeoutExpired as exc:
            self._json(500, {"error": "fixed diagnostic timed out", "operation": str(exc.cmd)})
        except Exception as exc:  # fail closed; keep caller-visible detail bounded
            self._json(500, {"error": "fixed resource measurement failed", "type": type(exc).__name__})
        finally:
            MEASUREMENT_LOCK.release()


def main() -> None:
    if not RISH.is_file():
        raise SystemExit(f"rish not found: {RISH}")
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(
        f"Liliya Qwen3 resource metrics bridge listening on http://{HOST}:{PORT}",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
