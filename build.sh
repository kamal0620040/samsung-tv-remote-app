#!/bin/bash
# Manual APK build, no Gradle required. Pipeline: kotlinc (in-process) -> d8
# -> aapt2 -> zipalign -> apksigner. Every step is a single visible command,
# dependencies are plain JARs, and the UI is framework Views only.
# Tool locations can be overridden: JAVA_HOME, ANDROID_HOME, M2, KEYSTORE.
# M2 must hold the dependency JARs named in KOTLINC_CP / DEPS below; they are
# the standard Maven artifacts (Kotlin 2.0.21, coroutines 1.9.0, OkHttp 4.12.0,
# Okio 3.6.0, JetBrains annotations 24.1.0, trove4j 1.0.20200330).
set -e
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-35/android.jar"
M2="${M2:-$HOME/.m2-tvremote}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"

KOTLINC_CP="$M2/kotlin-compiler-embeddable-2.0.21.jar:$M2/kotlin-stdlib-2.0.21.jar:$M2/kotlinx-coroutines-core-jvm-1.9.0.jar:$M2/annotations-24.1.0.jar:$M2/trove4j-1.0.20200330.jar"
DEPS="$M2/kotlin-stdlib-2.0.21.jar:$M2/kotlinx-coroutines-core-jvm-1.9.0.jar:$M2/kotlinx-coroutines-android-1.9.0.jar:$M2/okhttp-4.12.0.jar:$M2/okio-jvm-3.6.0.jar:$M2/annotations-24.1.0.jar"

# ---- preflight: fail early with an actionable message ----
missing=0
for tool in java zip unzip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing tool: $tool"; missing=1; }
done
for f in "$BT/d8" "$BT/aapt2" "$BT/zipalign" "$BT/apksigner" "$AJAR" "$KEYSTORE"; do
  [ -f "$f" ] || { echo "missing file: $f"; missing=1; }
done
for jar in $(echo "$KOTLINC_CP:$DEPS" | tr ':' ' '); do
  [ -f "$jar" ] || { echo "missing dependency JAR: $jar (see the M2 note at the top)"; missing=1; }
done
[ "$missing" -eq 0 ] || exit 1

build_app() {
  local APP="$1" OUTNAME="$2"
  local OUT=".build/$APP"
  rm -rf "$OUT"; mkdir -p "$OUT/dex" "$OUT/stage"
  echo "== [$APP] kotlinc =="
  find tvcore/src "$APP/src" -name '*.kt' | sort > "$OUT/sources.txt"
  # Check the compiler's real exit status (piping through grep would hide it).
  if ! java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$AJAR:$DEPS" -d "$OUT/app.jar" @"$OUT/sources.txt" > "$OUT/kotlinc.log" 2>&1; then
    grep -E "error:" "$OUT/kotlinc.log" | head -20
    echo "kotlinc failed; full log: $OUT/kotlinc.log"
    return 1
  fi
  grep -E "warning:" "$OUT/kotlinc.log" | head -5 || true
  echo "== [$APP] d8 =="
  "$BT/d8" --lib "$AJAR" --min-api 26 --output "$OUT/dex" "$OUT/app.jar" \
    "$M2/kotlin-stdlib-2.0.21.jar" "$M2/kotlinx-coroutines-core-jvm-1.9.0.jar" \
    "$M2/kotlinx-coroutines-android-1.9.0.jar" "$M2/okhttp-4.12.0.jar" \
    "$M2/okio-jvm-3.6.0.jar" "$M2/annotations-24.1.0.jar"
  echo "== [$APP] aapt2 =="
  "$BT/aapt2" compile --dir "$APP/src/main/res" -o "$OUT/res.zip"
  "$BT/aapt2" link -I "$AJAR" --manifest "$APP/src/main/AndroidManifest.xml" \
    "$OUT/res.zip" -o "$OUT/unsigned.apk"
  # dex (all of them: multidex output must not be dropped) + every
  # META-INF/services registration from every dependency JAR
  cp "$OUT"/dex/*.dex "$OUT/stage/"
  for jar in $(echo "$DEPS" | tr ':' ' '); do
    (cd "$OUT/stage" && unzip -o -q "$jar" 'META-INF/services/*' || true)
  done
  (cd "$OUT/stage" && zip -q -r "$OLDPWD/$OUT/unsigned.apk" .)
  echo "== [$APP] sign =="
  "$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
  mkdir -p dist
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "dist/$OUTNAME" "$OUT/aligned.apk"
  "$BT/apksigner" verify "dist/$OUTNAME"
  ls -lh "dist/$OUTNAME"
}

build_app app TV-Remote.apk
echo
echo "== badging =="
"$BT/aapt2" dump badging dist/TV-Remote.apk | head -2
echo BUILD_OK
