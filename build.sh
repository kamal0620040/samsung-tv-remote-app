#!/bin/bash
# Manual APK build, no Gradle required. Pipeline: kotlinc (in-process) -> d8
# -> aapt2 -> zipalign -> apksigner. Every step is a single visible command,
# dependencies are plain JARs, and the UI is framework Views only.
# Tool locations can be overridden: JAVA_HOME, ANDROID_HOME, M2, KEYSTORE.
# M2 must hold the dependency JARs named in KOTLINC_CP / DEPS below.
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

build_app() {
  local APP="$1" OUTNAME="$2"
  local OUT=".build/$APP"
  rm -rf "$OUT"; mkdir -p "$OUT/dex" "$OUT/stage"
  echo "== [$APP] kotlinc =="
  find tvcore/src "$APP/src" -name '*.kt' > "$OUT/sources.txt"
  java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$AJAR:$DEPS" -d "$OUT/app.jar" @"$OUT/sources.txt" 2>&1 | grep -E "error:|exception" && return 1 || true
  echo "== [$APP] d8 =="
  "$BT/d8" --lib "$AJAR" --min-api 26 --output "$OUT/dex" "$OUT/app.jar" \
    "$M2/kotlin-stdlib-2.0.21.jar" "$M2/kotlinx-coroutines-core-jvm-1.9.0.jar" \
    "$M2/kotlinx-coroutines-android-1.9.0.jar" "$M2/okhttp-4.12.0.jar" \
    "$M2/okio-jvm-3.6.0.jar" "$M2/annotations-24.1.0.jar"
  echo "== [$APP] aapt2 =="
  "$BT/aapt2" compile --dir "$APP/src/main/res" -o "$OUT/res.zip"
  "$BT/aapt2" link -I "$AJAR" --manifest "$APP/src/main/AndroidManifest.xml" \
    "$OUT/res.zip" -o "$OUT/unsigned.apk"
  # dex + ServiceLoader registrations (coroutines Dispatchers.Main factory)
  cp "$OUT/dex/classes.dex" "$OUT/stage/"
  (cd "$OUT/stage" && unzip -o -q "$M2/kotlinx-coroutines-android-1.9.0.jar" 'META-INF/services/*' || true)
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
