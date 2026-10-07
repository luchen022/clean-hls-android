#!/usr/bin/env bash
set -euo pipefail
sdk_dir="${ANDROID_HOME:-/tmp/android-sdk}"
root_dir="$(cd "$(dirname "$0")" && pwd)"
project_build="$root_dir/build"
bt="$sdk_dir/build-tools/35.0.0"
mkdir -p "$project_build/classes" "$project_build/dex" "$root_dir/dist"
"$bt/aapt2" link --manifest "$root_dir/app/src/main/AndroidManifest.xml" -I "$sdk_dir/platforms/android-35/android.jar" --min-sdk-version 26 --target-sdk-version 35 -o "$project_build/base.apk"
java com.sun.tools.javac.Main --release 8 -encoding UTF-8 -cp "$sdk_dir/platforms/android-35/android.jar" -d "$project_build/classes" "$root_dir"/app/src/main/java/com/example/cleanhls/*.java
(cd "$project_build/classes" && zip -q -r "$project_build/classes.jar" .)
"$bt/d8" --min-api 26 --lib "$sdk_dir/platforms/android-35/android.jar" --output "$project_build/dex" "$project_build/classes.jar"
cp "$project_build/base.apk" "$project_build/unsigned.apk"
(cd "$project_build/dex" && zip -q "$project_build/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$project_build/unsigned.apk" "$project_build/aligned.apk"
if [ ! -f "$root_dir/debug.keystore" ]; then
  keytool -genkeypair -keystore "$root_dir/debug.keystore" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Clean HLS Debug,O=Independent,C=US' -keyalg RSA -keysize 2048 -validity 3650 >/dev/null 2>&1
fi
"$bt/apksigner" sign --ks "$root_dir/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$root_dir/dist/clean-hls-debug.apk" "$project_build/aligned.apk"
"$bt/apksigner" verify --verbose "$root_dir/dist/clean-hls-debug.apk"
