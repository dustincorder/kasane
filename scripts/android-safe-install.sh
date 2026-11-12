#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <adb-serial> <signed-release.apk>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
serial="$1"
apk="$2"
[[ -n "$serial" && -f "$apk" ]] || usage

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
adb_bin="${ADB:-$sdk_root/platform-tools/adb}"
aapt_bin="${AAPT:-$sdk_root/build-tools/36.0.0/aapt}"
apksigner_bin="${APKSIGNER:-$sdk_root/build-tools/36.0.0/apksigner}"
for tool in "$adb_bin" "$aapt_bin" "$apksigner_bin"; do
  [[ -x "$tool" ]] || { echo "Required Android tool not found: $tool" >&2; exit 2; }
done

badging="$($aapt_bin dump badging "$apk")"
package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
candidate_version="$(sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)"
[[ "$package" == "cord.kasane.music" ]] || { echo "Refusing non-release Kasane package: $package" >&2; exit 3; }
[[ "$candidate_version" =~ ^[0-9]+$ ]] || { echo "Could not read candidate versionCode." >&2; exit 3; }

device_state="$($adb_bin devices -l | awk -v s="$serial" '$1 == s { print $2; exit }')"
[[ "$device_state" == "device" ]] || { echo "Selected ADB device is not connected and authorized." >&2; exit 4; }

installed_paths="$($adb_bin -s "$serial" shell pm path "$package" | tr -d '\r')"
[[ -n "$installed_paths" ]] || { echo "Kasane is not installed; refusing a fresh install." >&2; exit 5; }
installed_path="$(sed -n '1s/^package://p' <<<"$installed_paths")"
[[ -n "$installed_path" ]] || { echo "Could not resolve installed base APK." >&2; exit 5; }

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
installed_apk="$tmpdir/installed-base.apk"
if ! "$adb_bin" -s "$serial" pull "$installed_path" "$installed_apk" >/dev/null; then
  echo "Could not inspect installed APK; refusing to install." >&2
  exit 6
fi

signer_digest() {
  "$apksigner_bin" verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr '[:upper:]' '[:lower:]'
}
installed_signer="$(signer_digest "$installed_apk")"
candidate_signer="$(signer_digest "$apk")"
[[ -n "$installed_signer" && -n "$candidate_signer" ]] || { echo "Could not verify APK signer; refusing to install." >&2; exit 7; }
[[ "$installed_signer" == "$candidate_signer" ]] || { echo "APK signer mismatch; no uninstall or install was attempted." >&2; exit 8; }

installed_badging="$($aapt_bin dump badging "$installed_apk")"
installed_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$installed_badging" | head -1)"
installed_version="$(sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" <<<"$installed_badging" | head -1)"
[[ "$installed_package" == "$package" ]] || { echo "Installed package mismatch; refusing to update." >&2; exit 9; }
[[ "$installed_version" =~ ^[0-9]+$ && "$candidate_version" -ge "$installed_version" ]] || {
  echo "Candidate version is invalid or older; refusing to update." >&2
  exit 10
}

echo "Verified in-place update: $package $installed_version -> $candidate_version; signer matches."
echo "Installing with adb install -r only; no uninstall fallback is permitted."
"$adb_bin" -s "$serial" install -r "$apk"

updated_paths="$($adb_bin -s "$serial" shell pm path "$package" | tr -d '\r')"
[[ -n "$updated_paths" ]] || { echo "Update command returned but package is no longer installed." >&2; exit 11; }
updated_path="$(sed -n '1s/^package://p' <<<"$updated_paths")"
updated_apk="$tmpdir/updated-base.apk"
"$adb_bin" -s "$serial" pull "$updated_path" "$updated_apk" >/dev/null
updated_signer="$(signer_digest "$updated_apk")"
[[ "$updated_signer" == "$installed_signer" ]] || { echo "Post-update signer check failed." >&2; exit 12; }
echo "In-place package update verified. No uninstall/clear fallback was used."
