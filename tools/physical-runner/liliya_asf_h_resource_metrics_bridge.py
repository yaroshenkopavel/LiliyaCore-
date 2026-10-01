#!/usr/bin/env python3
"""Local-only fixed-purpose ASF-H physical resource evidence bridge.

Security properties:
- binds only 127.0.0.1;
- requires the existing Termux-private bearer token;
- accepts no arbitrary command, package, test class, path, or shell input;
- runs exactly two fixed instrumentation modes;
- samples fixed Android diagnostics only;
- reads only two fixed app-private summary files;
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
PORT = int(os.environ.get("LILIYA_ASF_H_METRICS_BRIDGE_PORT", "18767"))
TERMUX_HOME = Path(os.environ.get("HOME", "/data/data/com.termux/files/home"))
RISH = Path(os.environ.get("LILIYA_RISH_PATH", "/data/data/com.termux/files/usr/bin/rish"))
TOKEN_FILE = Path(
    os.environ.get(
        "LILIYA_PHYSICAL_BRIDGE_TOKEN_FILE",
        str(TERMUX_HOME / ".liliya-physical-bridge-token"),
    )
)

APP_PACKAGE = "pro.liliya.app"
TEST_PACKAGE = "pro.liliya.app.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "pro.liliya.app.AsfHierarchicalPhysicalResourceInstrumentedTest"

MODE_FULL = "FULL_ONLY"
MODE_HIERARCHICAL = "HIERARCHICAL"
MODES = (MODE_FULL, MODE_HIERARCHICAL)

SUMMARY_FILES = {
    MODE_FULL: "files/asf-h-physical-full-only.json",
    MODE_HIERARCHICAL: "files/asf-h-physical-hierarchical.json",
}

SAMPLE_INTERVAL_SECONDS = 0.25
THERMAL_SAMPLE_EVERY = 8
INSTRUMENTATION_TIMEOUT_SECONDS = 900
COOLDOWN_SECONDS = 3
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


def fixed_device_probe() -> dict[str, object]:
    commands = {
        "shellIdentity": "id",
        "model": "getprop ro.product.model",
        "primaryAbi": "getprop ro.product.cpu.abi",
        "sdk": "getprop ro.build.version.sdk",
        "qemu": "getprop ro.kernel.qemu",
        "appPath": f"pm path {APP_PACKAGE}",
        "testPath": f"pm path {TEST_PACKAGE}",
    }
    result: dict[str, object] = {
        "evidenceClass": "asf-h-resource-metrics-bridge-probe-v1",
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


def take_sample(index: int, include_thermal: bool) -> dict[str, object]:
    mem = diagnostic(f"dumpsys meminfo {APP_PACKAGE}", timeout=30)
    sample: dict[str, object] = {
        "index": index,
        "epochSeconds": time.time(),
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


def run_fixed_instrumentation_with_samples(
    mode: str,
    started_epoch: float,
) -> tuple[int, str, list[dict[str, object]]]:
    suffix = "full" if mode == MODE_FULL else "hierarchical"
    output_path = f"/data/local/tmp/liliya_asf_h_{suffix}.instrumentation.out"
    command = (
        f"rm -f {output_path}; "
        "am instrument -w -r "
        f"-e class {TEST_CLASS} "
        f"-e asf_h_mode {mode} "
        f"{TEST_PACKAGE}/{RUNNER} >{output_path} 2>&1 & "
        "p=$!; i=0; "
        "while kill -0 \"$p\" 2>/dev/null; do "
        "echo __ASF_H_MEM_SAMPLE_BEGIN__:$i; "
        f"dumpsys meminfo {APP_PACKAGE} "
        "| grep -E 'TOTAL PSS|TOTAL RSS|No process found' | head -1 || true; "
        "echo __ASF_H_MEM_SAMPLE_END__:$i; "
        "i=$((i+1)); "
        f"sleep {SAMPLE_INTERVAL_SECONDS}; "
        "done; "
        "wait \"$p\"; rc=$?; "
        "echo __ASF_H_INSTRUMENTATION_EXIT__:$rc; "
        "echo __ASF_H_INSTRUMENTATION_OUTPUT_BEGIN__; "
        f"cat {output_path}; "
        "echo __ASF_H_INSTRUMENTATION_OUTPUT_END__; "
        "exit 0"
    )
    completed = run_rish(
        command,
        timeout=INSTRUMENTATION_TIMEOUT_SECONDS + 60,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"fixed instrumentation shell failed for {mode}: {completed.returncode}"
        )

    samples: list[dict[str, object]] = []
    instrumentation_exit: int | None = None
    instrumentation_lines: list[str] = []
    current_sample_index: int | None = None
    current_sample_lines: list[str] = []
    in_instrumentation_output = False

    for line in completed.stdout.splitlines():
        if line.startswith("__ASF_H_MEM_SAMPLE_BEGIN__:"):
            current_sample_index = int(line.rsplit(":", 1)[1])
            current_sample_lines = []
            continue
        if line.startswith("__ASF_H_MEM_SAMPLE_END__:"):
            if current_sample_index is not None:
                raw_memory = "\n".join(current_sample_lines).strip()
                parsed_memory = parse_meminfo(raw_memory)
                samples.append(
                    {
                        "index": current_sample_index,
                        "epochSeconds": (
                            started_epoch
                            + current_sample_index * SAMPLE_INTERVAL_SECONDS
                        ),
                        "memory": {
                            **parsed_memory,
                            "exit": 0,
                            "raw": bounded_raw(raw_memory),
                        },
                    }
                )
            current_sample_index = None
            current_sample_lines = []
            continue
        if line.startswith("__ASF_H_INSTRUMENTATION_EXIT__:"):
            instrumentation_exit = int(line.rsplit(":", 1)[1])
            continue
        if line == "__ASF_H_INSTRUMENTATION_OUTPUT_BEGIN__":
            in_instrumentation_output = True
            continue
        if line == "__ASF_H_INSTRUMENTATION_OUTPUT_END__":
            in_instrumentation_output = False
            continue
        if current_sample_index is not None:
            current_sample_lines.append(line)
            continue
        if in_instrumentation_output:
            instrumentation_lines.append(line)

    if instrumentation_exit is None:
        raise RuntimeError(f"instrumentation exit marker missing for {mode}")

    return (
        instrumentation_exit,
        "\n".join(instrumentation_lines).strip(),
        samples,
    )


def read_fixed_summary(mode: str) -> dict[str, object]:
    path = SUMMARY_FILES[mode]
    completed = run_rish(f"run-as {APP_PACKAGE} cat {path}", timeout=30)
    if completed.returncode != 0:
        raise RuntimeError(f"cannot read fixed summary for {mode}")
    parsed = json.loads(completed.stdout)
    if parsed.get("mode") != mode:
        raise RuntimeError(f"summary mode mismatch for {mode}")
    if parsed.get("evidenceClass") != "asf-h-physical-synthetic-runtime-summary-v1":
        raise RuntimeError(f"summary evidence class mismatch for {mode}")
    return parsed


def remove_fixed_summary(mode: str) -> None:
    path = SUMMARY_FILES[mode]
    run_rish(f"run-as {APP_PACKAGE} rm -f {path}", timeout=30)


def run_mode(mode: str) -> dict[str, object]:
    if mode not in MODES:
        raise RuntimeError("unsupported fixed ASF-H mode")

    remove_fixed_summary(mode)

    battery_before_raw = diagnostic("dumpsys battery", timeout=30)
    thermal_before = diagnostic("dumpsys thermalservice", timeout=30)
    started_epoch = time.time()

    instrumentation_exit, output, samples = run_fixed_instrumentation_with_samples(
        mode,
        started_epoch,
    )

    ended_epoch = time.time()
    battery_after_raw = diagnostic("dumpsys battery", timeout=30)
    thermal_after = diagnostic("dumpsys thermalservice", timeout=30)

    instrumentation_ok = (
        instrumentation_exit == 0
        and "INSTRUMENTATION_CODE: -1" in output
        and "FAILURES!!!" not in output
        and "INSTRUMENTATION_FAILED" not in output
        and "Process crashed" not in output
    )

    summary = read_fixed_summary(mode) if instrumentation_ok else None

    return {
        "mode": mode,
        "instrumentationPassed": instrumentation_ok,
        "instrumentationExit": instrumentation_exit,
        "instrumentationTimedOut": False,
        "instrumentationOutput": bounded_raw(output),
        "startedEpochSeconds": started_epoch,
        "endedEpochSeconds": ended_epoch,
        "elapsedMillis": int(round((ended_epoch - started_epoch) * 1000)),
        "sampleIntervalSeconds": SAMPLE_INTERVAL_SECONDS,
        "sampleCount": len(samples),
        "peakTotalPssKb": peak_metric(samples, "totalPssKb"),
        "peakTotalRssKb": peak_metric(samples, "totalRssKb"),
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
        "testSummary": summary,
        "samples": samples,
    }


def run_comparison() -> dict[str, object]:
    started = time.time()
    full = run_mode(MODE_FULL)
    time.sleep(COOLDOWN_SECONDS)
    hierarchical = run_mode(MODE_HIERARCHICAL)
    ended = time.time()

    passed = bool(full["instrumentationPassed"] and hierarchical["instrumentationPassed"])

    return {
        "evidenceClass": "asf-h-android-physical-resource-evidence-v1",
        "bridgeBackend": "shizuku-rish",
        "appPackage": APP_PACKAGE,
        "testPackage": TEST_PACKAGE,
        "testClass": TEST_CLASS,
        "workloadProfile": "physical-synthetic-bounded-runtime-v1",
        "cooldownSeconds": COOLDOWN_SECONDS,
        "startedEpochSeconds": started,
        "endedEpochSeconds": ended,
        "elapsedMillis": int(round((ended - started) * 1000)),
        "instrumentationPassed": passed,
        "fullOnly": full,
        "hierarchical": hierarchical,
        "verdict": "MEASURED_NO_PRODUCT_BUDGET_APPLIED",
    }


TOKEN = load_token()
MEASUREMENT_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server_version = "LiliyaAsfHResourceMetricsBridge/1"

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("asf-h-metrics-bridge: " + (fmt % args) + "\n")

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
        if path != "/v1/instrument/asf-h-resources":
            self._json(404, {"error": "unknown endpoint"})
            return

        if not MEASUREMENT_LOCK.acquire(blocking=False):
            self._json(409, {"error": "resource measurement already running"})
            return
        try:
            result = run_comparison()
            self._json(200 if result["instrumentationPassed"] else 500, result)
        except subprocess.TimeoutExpired as exc:
            self._json(500, {"error": "fixed diagnostic timed out", "operation": str(exc.cmd)})
        except Exception as exc:
            self._json(
                500,
                {
                    "error": "fixed ASF-H resource measurement failed",
                    "type": type(exc).__name__,
                },
            )
        finally:
            MEASUREMENT_LOCK.release()


def main() -> None:
    if not RISH.is_file():
        raise SystemExit(f"rish not found: {RISH}")
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(
        f"Liliya ASF-H resource metrics bridge listening on http://{HOST}:{PORT}",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
