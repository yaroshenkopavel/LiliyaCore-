#!/usr/bin/env bash
# Read-only preflight for the isolated ASF-H physical APK pair.
# Usage: bash tools/physical-runner/Verify-AsfHIsolatedBundle.sh /path/to/extracted-artifact <exact-40-character-source-sha>
set -euo pipefail
dir="${1:?expected extracted artifact directory}"
expected_source_sha="${2:?expected exact 40-character source SHA}"
[[ "$expected_source_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "FATAL: source SHA must be 40 lower-case hex digits" >&2; exit 1; }
manifest="$dir/asf-h-physical-test-bundle.json"
test -f "$manifest"
command -v python3 >/dev/null
command -v sha256sum >/dev/null
command -v aapt >/dev/null
command -v apksigner >/dev/null
command -v rish >/dev/null

python3 - "$dir" "$expected_source_sha" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve(strict=True)
manifest = json.loads((root / "asf-h-physical-test-bundle.json").read_text())
assert manifest.get("evidenceClass") == "asf-h-physical-test-bundle-v1"
assert manifest.get("applicationId") == "pro.liliya.app.asfh"
assert manifest.get("testApplicationId") == "pro.liliya.app.asfh.test"
assert manifest.get("workloadProfile") == "physical-synthetic-bounded-runtime-v1"
assert manifest.get("sourceCommit") == sys.argv[2], "source commit mismatch"
assert set(manifest.get("files", {})) == {"liliya-app-debug.apk", "liliya-app-debug-androidTest.apk"}
for filename, evidence in manifest["files"].items():
    apk = (root / filename).resolve(strict=True)
    assert apk.parent == root, "out-of-directory bundle path"
    assert apk.stat().st_size == evidence["size"], filename + " size mismatch"
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == evidence["sha256"], filename + " SHA256 mismatch"
print("ASF_H_ISOLATED_MANIFEST_AND_APK_HASHES=PASS")
print("SOURCE_COMMIT=" + manifest["sourceCommit"])
PY

for pair in "liliya-app-debug.apk pro.liliya.app.asfh" "liliya-app-debug-androidTest.apk pro.liliya.app.asfh.test"; do
  read -r file expected <<< "$pair"
  apk="$dir/$file"
  actual="$(aapt dump badging "$apk" | sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" | head -1)"
  if [[ "$actual" != "$expected" ]]; then
    echo "FATAL: APK package mismatch for $file: $actual" >&2
    exit 1
  fi
  apksigner verify --print-certs "$apk" > /dev/null
  echo "PACKAGE_AND_SIGNATURE_VERIFIED=$expected"
done

app_cert="$(apksigner verify --print-certs "$dir/liliya-app-debug.apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
test_cert="$(apksigner verify --print-certs "$dir/liliya-app-debug-androidTest.apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
[[ -n "$app_cert" && "$app_cert" == "$test_cert" ]] || {
  echo "FATAL: two test APK signing certificates differ" >&2
  exit 1
}
echo "ISOLATED_PAIR_SIGNING_CERT_MATCH=PASS"

for package in pro.liliya.app.asfh pro.liliya.app.asfh.test; do
  installed="$(rish -c "pm path $package")"
  if [[ "$installed" == *"package:"* ]]; then
    echo "FATAL: isolated package already installed ($package); refusing automatic update" >&2
    exit 1
  fi
  echo "ISOLATED_PACKAGE_ABSENT=$package"
done

for package in pro.liliya.app pro.liliya.app.test; do
  installed="$(rish -c "pm path $package")"
  [[ "$installed" == *"package:"* ]] || {
    echo "FATAL: existing production package unexpectedly missing: $package" >&2
    exit 1
  }
  echo "PRODUCTION_PACKAGE_UNCHANGED=$package"
done

echo "ASF_H_READ_ONLY_FIRST_INSTALL_PREFLIGHT=PASS"
echo "NO_APK_HAS_BEEN_INSTALLED"
