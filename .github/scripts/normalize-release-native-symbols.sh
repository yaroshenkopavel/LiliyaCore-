#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: normalize-release-native-symbols.sh <unsigned-apk>}"
ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"

if [[ -z "$ndk" ]]; then
  echo "ANDROID_NDK_ROOT/ANDROID_NDK_HOME is required" >&2
  exit 1
fi

strip="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
zipalign="$ANDROID_HOME/build-tools/35.0.0/zipalign"

for tool in "$strip" "$readelf" "$zipalign"; do
  test -x "$tool" || { echo "missing tool: $tool" >&2; exit 1; }
done
test -f "$apk"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mapfile -t libs < <(unzip -Z1 "$apk" | grep -E '^lib/arm64-v8a/[^/]+\.so$' | sort)
test "${#libs[@]}" -gt 0

apk_abs="$(realpath "$apk")"

for entry in "${libs[@]}"; do
  src="$work/original/$(basename "$entry")"
  dst="$work/repack/$entry"
  mkdir -p "$(dirname "$src")" "$(dirname "$dst")"

  unzip -p "$apk" "$entry" > "$src"
  cp "$src" "$dst"

  before="$(stat --format=%s "$src")"
  "$strip" --strip-all "$dst"
  after="$(stat --format=%s "$dst")"

  if "$readelf" --sections "$dst" | grep -Eq '\.debug_|\.symtab'; then
    echo "native debug/symbol section remains after strip: $entry" >&2
    exit 1
  fi

  echo "NATIVE_STRIP entry=$entry before=$before after=$after"
done

for entry in "${libs[@]}"; do
  zip -dq "$apk_abs" "$entry"
done

(
  cd "$work/repack"
  zip -0Xq "$apk_abs" "${libs[@]}"
)

aligned="$work/aligned.apk"
"$zipalign" -f -P 16 4 "$apk_abs" "$aligned"
cp "$aligned" "$apk_abs"

"$zipalign" -c -P 16 -v 4 "$apk_abs"
echo "NATIVE_RELEASE_NORMALIZATION=PASS"
