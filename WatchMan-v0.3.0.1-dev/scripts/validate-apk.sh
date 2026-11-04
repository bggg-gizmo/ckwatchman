#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
APK="${1:-$ROOT/build-local/WatchMan-v0.3.0-unsigned.apk}"
AAPT2="${AAPT2:-$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2}"
ZIPALIGN="${ZIPALIGN:-$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign}"
D8_JAR="${D8_JAR:-${R8_JAR:-$ANDROID_SDK_ROOT/build-tools/36.0.0/lib/d8.jar}}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK_ROOT/platforms/android-36/android.jar}"

[[ -f "$APK" ]] || { echo "Missing APK: $APK" >&2; exit 2; }
[[ -x "$AAPT2" ]] || { echo "Missing aapt2: $AAPT2" >&2; exit 2; }
[[ -x "$ZIPALIGN" ]] || { echo "Missing zipalign: $ZIPALIGN" >&2; exit 2; }
[[ -f "$D8_JAR" ]] || { echo "Missing D8/R8 jar: $D8_JAR" >&2; exit 2; }
[[ -f "$ANDROID_JAR" ]] || { echo "Missing Android platform jar: $ANDROID_JAR" >&2; exit 2; }

LIB64="$(dirname "$ZIPALIGN")/lib64"
if [[ -d "$LIB64" ]]; then export LD_LIBRARY_PATH="$LIB64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; fi

unzip -t "$APK" >/dev/null
for required in AndroidManifest.xml resources.arsc classes.dex; do
  unzip -Z1 "$APK" | grep -qx "$required" || { echo "Missing root $required" >&2; exit 1; }
done
"$ZIPALIGN" -c -P 16 4 "$APK"
BADGING="$($AAPT2 dump badging "$APK")"
printf '%s\n' "$BADGING" | grep -q "package: name='com.bggg.watchman' versionCode='30' versionName='0.3.0'"
printf '%s\n' "$BADGING" | grep -q "launchable-activity: name='com.bggg.watchman.MainActivity'"
printf '%s\n' "$BADGING" | grep -E "^(package|minSdkVersion|targetSdkVersion|application-label|launchable-activity)"

if unzip -Z1 "$APK" | grep -Eqi '^META-INF/.*\.(RSA|DSA|EC|SF)$'; then
  echo "Unexpected APK signature payload" >&2; exit 1
fi
if unzip -p "$APK" 'classes*.dex' | strings | grep -Eqi 'android/webkit|WebView|loadUrl|javascript:'; then
  echo "Unexpected WebView/browser wrapper reference" >&2; exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" 'classes*.dex' -d "$TMP/in"
mkdir -p "$TMP/out"
mapfile -t DEX < <(find "$TMP/in" -type f -name 'classes*.dex' | sort)
java -cp "$D8_JAR" com.android.tools.r8.D8 --lib "$ANDROID_JAR" --min-api 26 --output "$TMP/out" "${DEX[@]}"

echo "APK static validation: PASS"
sha256sum "$APK"
stat -c '%s bytes' "$APK"
