#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.1.0}"
PLATFORM="${ANDROID_PLATFORM:-android-36}"
BUILD_TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_ROOT/platforms/$PLATFORM/android.jar"
OUT="$PROJECT_DIR/build"
JAVAC="${JAVAC:-$(command -v javac || true)}"

if [[ -z "$JAVAC" ]]; then
    for candidate in "$HOME"/.jdks/*/bin/javac; do
        if [[ -x "$candidate" ]]; then
            JAVAC="$candidate"
            break
        fi
    done
fi
if [[ -z "$JAVAC" || ! -x "$JAVAC" ]]; then
    echo "Missing javac; install a JDK or set JAVAC=/path/to/javac" >&2
    exit 1
fi

for tool in aapt2 d8 zipalign apksigner; do
    if [[ ! -x "$BUILD_TOOLS/$tool" ]]; then
        echo "Missing Android build tool: $BUILD_TOOLS/$tool" >&2
        exit 1
    fi
done
if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "Missing Android platform jar: $ANDROID_JAR" >&2
    exit 1
fi

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/generated"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/generated"

"$BUILD_TOOLS/aapt2" compile \
    --dir "$PROJECT_DIR/app/src/main/res" \
    -o "$OUT/compiled-resources.zip"

"$BUILD_TOOLS/aapt2" link \
    -o "$OUT/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
    --min-sdk-version 25 \
    --target-sdk-version 25 \
    --version-code 1 \
    --version-name 0.1 \
    --auto-add-overlay \
    "$OUT/compiled-resources.zip"

mapfile -t JAVA_SOURCES < <(find "$PROJECT_DIR/app/src/main/java" -name '*.java' -print | sort)
"$JAVAC" -source 8 -target 8 -classpath "$ANDROID_JAR" -d "$OUT/classes" "${JAVA_SOURCES[@]}"
mapfile -t CLASS_FILES < <(find "$OUT/classes" -name '*.class' -print | sort)
"$BUILD_TOOLS/d8" --min-api 25 --lib "$ANDROID_JAR" --output "$OUT/dex" "${CLASS_FILES[@]}"

cp "$OUT/unsigned.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unaligned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [[ ! -f "$OUT/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$OUT/debug.keystore" -storepass android \
        -keypass android -alias batteryguardian -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Battery Guardian Debug,O=Local Build,C=XX" >/dev/null 2>&1
fi
"$BUILD_TOOLS/apksigner" sign --ks "$OUT/debug.keystore" --ks-key-alias batteryguardian \
    --ks-pass pass:android --key-pass pass:android --out "$OUT/battery-guardian.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$OUT/battery-guardian.apk"
echo "Built $OUT/battery-guardian.apk"
