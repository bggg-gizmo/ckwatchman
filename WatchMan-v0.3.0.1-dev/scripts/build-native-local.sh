#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${BUILD_DIR:-$ROOT/build-local}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK_ROOT/platforms/android-36/android.jar}"
AAPT2="${AAPT2:-$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2}"
D8_JAR="${D8_JAR:-${R8_JAR:-$ANDROID_SDK_ROOT/build-tools/36.0.0/lib/d8.jar}}"
ZIPALIGN="${ZIPALIGN:-$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign}"
KOTLINC="${KOTLINC:-$(command -v kotlinc || true)}"
KOTLIN_HEAP="${KOTLIN_HEAP:-1536m}"

fail() { echo "Build error: $*" >&2; exit 2; }
for cmd in java javac jar zip unzip sha256sum stat; do command -v "$cmd" >/dev/null 2>&1 || fail "missing host command: $cmd"; done
[[ -n "$KOTLINC" && -x "$KOTLINC" ]] || fail "missing executable Kotlin compiler (KOTLINC=$KOTLINC)"
[[ -f "$ANDROID_JAR" ]] || fail "missing Android platform jar: $ANDROID_JAR"
[[ -x "$AAPT2" ]] || fail "missing/executable aapt2: $AAPT2"
[[ -f "$D8_JAR" ]] || fail "missing official D8/R8 jar: $D8_JAR"
[[ -x "$ZIPALIGN" ]] || fail "missing/executable zipalign: $ZIPALIGN"

KLIB="${KOTLIN_LIB:-$(cd "$(dirname "$KOTLINC")/../lib" && pwd)}"
for lib in kotlin-stdlib.jar kotlin-stdlib-jdk7.jar kotlin-stdlib-jdk8.jar; do
  [[ -f "$KLIB/$lib" ]] || fail "missing Kotlin runtime jar: $KLIB/$lib"
done

rm -rf "$BUILD"
mkdir -p "$BUILD/compiled_res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/dex-verify"

# 1) Resources -> compiled .flat resources.
find "$ROOT/app/src/main/res" -type f -print0 | xargs -0 "$AAPT2" compile -o "$BUILD/compiled_res"
RES=()
while IFS= read -r -d '' f; do RES+=("$f"); done < <(find "$BUILD/compiled_res" -type f -print0)
((${#RES[@]})) || fail "aapt2 compile produced no resources"

# 2) Link resource shell and generate R.java.
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code 30 \
  --version-name 0.3.0 \
  -A "$ROOT/app/src/main/assets" \
  "${RES[@]}"

# 3) Compile generated R.java then all Kotlin application sources.
mapfile -d '' RJAVA < <(find "$BUILD/gen" -name '*.java' -print0)
if ((${#RJAVA[@]})); then
  javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" "${RJAVA[@]}"
fi
mapfile -d '' KTS < <(find "$ROOT/app/src/main/java" -name '*.kt' -print0)
((${#KTS[@]})) || fail "no Kotlin application sources found"
"$KOTLINC" -J-Xmx"$KOTLIN_HEAP" "${KTS[@]}" -classpath "$ANDROID_JAR:$BUILD/classes" -jvm-target 1.8 -d "$BUILD/classes"

# 4) Standard JVM program jar -> official D8.
jar cf "$BUILD/app-classes.jar" -C "$BUILD/classes" .
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$BUILD/dex" \
  "$BUILD/app-classes.jar" \
  "$KLIB/kotlin-stdlib.jar" \
  "$KLIB/kotlin-stdlib-jdk7.jar" \
  "$KLIB/kotlin-stdlib-jdk8.jar"

mapfile -t DEX_FILES < <(find "$BUILD/dex" -maxdepth 1 -type f -name 'classes*.dex' | sort)
((${#DEX_FILES[@]})) || fail "D8 produced no classes*.dex"

# Re-feed the generated DEX through official D8 as a structural parse/rewrite gate.
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$BUILD/dex-verify" \
  "${DEX_FILES[@]}"

# 5) Assemble and align. Release builds do not fall back to an unaligned APK.
cp "$BUILD/base.apk" "$BUILD/unaligned.apk"
(
  cd "$BUILD/dex"
  zip -q -j "$BUILD/unaligned.apk" classes*.dex
)

OUT="$BUILD/WatchMan-v0.3.0-unsigned.apk"
LIB64="$(dirname "$ZIPALIGN")/lib64"
if [[ -d "$LIB64" ]]; then export LD_LIBRARY_PATH="$LIB64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; fi
"$ZIPALIGN" -f -P 16 4 "$BUILD/unaligned.apk" "$OUT"
"$ZIPALIGN" -c -P 16 4 "$OUT"

# 6) Final unsigned/package checks.
unzip -t "$OUT" >/dev/null
for required in AndroidManifest.xml resources.arsc classes.dex; do
  unzip -Z1 "$OUT" | grep -qx "$required" || fail "APK missing root $required"
done
if unzip -Z1 "$OUT" | grep -Eqi '^META-INF/.*\.(RSA|DSA|EC|SF)$'; then
  fail "unexpected APK signature payload"
fi
BADGING="$BUILD/badging.txt"
"$AAPT2" dump badging "$OUT" > "$BADGING"
grep -q "package: name='com.bggg.watchman' versionCode='30' versionName='0.3.0'" "$BADGING" || fail "APK package/version metadata mismatch"
grep -q "launchable-activity: name='com.bggg.watchman.MainActivity'" "$BADGING" || fail "MainActivity is not the launchable activity"

sha256sum "$OUT"
stat -c '%s bytes' "$OUT"
echo "$OUT"
