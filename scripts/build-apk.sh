#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}
tools="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" dist

if [[ -n ${ECJ_JAR:-} ]]; then
  compiler=(java -jar "$ECJ_JAR" -nowarn)
else
  compiler=(javac)
fi
"${compiler[@]}" -encoding UTF-8 -source 8 -target 8 -classpath "$platform" \
  -d "$work/classes" src/app/fastshare/*.java
(cd "$work/classes" && zip -qr ../classes.jar .)
"$tools/d8" --release --min-api 21 --lib "$platform" --output "$work/dex" "$work/classes.jar"
"$tools/aapt2" link -I "$platform" --manifest AndroidManifest.xml -o "$work/unsigned.apk"
(cd "$work/dex" && zip -q -0 ../unsigned.apk classes.dex)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/debug.p12" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug'
"$tools/apksigner" sign --ks "$work/debug.p12" --ks-pass pass:android \
  --v4-signing-enabled false --out dist/FastShare.apk "$work/aligned.apk"
"$tools/apksigner" verify --verbose dist/FastShare.apk
