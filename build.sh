#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/.local/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk}"
PATH="$JAVA_HOME/bin:$PATH"

# Signing material is never committed. Override these for your own key.
KS="${AURORA_KEYSTORE:-keystore.jks}"
KS_PASS="${AURORA_KEYSTORE_PASS:-aurora}"
KEY_PASS="${AURORA_KEY_PASS:-$KS_PASS}"

if [[ ! -x "$BT/aapt2" ]]; then
  echo "Android build-tools 35.0.0 not found in $SDK" >&2
  exit 1
fi

cd "$ROOT"
rm -rf build
mkdir -p build/gen build/classes build/dex build/apk

echo "==> native remapper"
NDK="${ANDROID_NDK_ROOT:-$HOME/Work/.local-toolchain/android-ndk-r27c}"
NATIVE_CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang"
if [[ -x "$NATIVE_CC" ]]; then
  mkdir -p assets
  "$NATIVE_CC" -O2 -Wall -Wextra -fPIE -pie -s \
    -o assets/aurora-remap native/aurora-remap.c
  "$NATIVE_CC" -O2 -Wall -Wextra -fPIE -pie -s \
    -o assets/aurora-touchmap native/aurora-touchmap.c -lm
else
  echo "Android NDK compiler not found at $NATIVE_CC" >&2
  exit 1
fi

echo "==> resources"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "==> link"
"$BT/aapt2" link \
  -o build/apk/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -A assets \
  build/res.zip \
  --java build/gen \
  --min-sdk-version 31 \
  --target-sdk-version 35 \
  --version-code 2 \
  --version-name 1.1.0

echo "==> compile"
javac -Xlint:-options -source 17 -target 17 \
  -classpath "$PLATFORM" \
  -d build/classes \
  $(find src build/gen -name '*.java')

echo "==> dex"
"$BT/d8" --lib "$PLATFORM" --min-api 31 --release \
  --output build/dex \
  $(find build/classes -name '*.class')

echo "==> package"
cp build/apk/base.apk build/apk/unsigned.apk
(cd build/dex && zip -q -j ../apk/unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/apk/unsigned.apk build/apk/aligned.apk

if [[ ! -f "$KS" ]]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias aurora \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Aurora, O=Aurora, C=US" >/dev/null 2>&1
fi

"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out Aurora.apk build/apk/aligned.apk

"$BT/apksigner" verify Aurora.apk
ls -lh Aurora.apk
