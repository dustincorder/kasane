#!/usr/bin/env bash
set -euo pipefail
# Standalone test instrumentation exclusively in an explicitly selected disposable emulator.
serial="${1:?Pass the disposable emulator serial}"
[[ "$serial" == emulator-* ]] || { echo 'Physical devices are forbidden.' >&2; exit 2; }
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
adb_bin="$sdk_root/platform-tools/adb"
aapt_bin="$sdk_root/build-tools/36.0.0/aapt"
[[ "$("$adb_bin" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 3
app=android/app/build/outputs/apk/debug/app-debug.apk
test=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ "$("$aapt_bin" dump badging "$app" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")" == cord.kasane.music.dev ]] || exit 4
[[ "$("$aapt_bin" dump badging "$test" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")" == cord.kasane.music.dev.test ]] || exit 5
"$adb_bin" -s "$serial" install -r "$app"
"$adb_bin" -s "$serial" install -r "$test"
result="$("$adb_bin" -s "$serial" shell am instrument -w cord.kasane.music.dev.test/cord.kasane.music.HlsPlaybackInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"HLS playback passed"* ]] || exit 6
