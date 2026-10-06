#!/usr/bin/env bash
# Builds both APKs and leaves them, named for the app, somewhere they can be
# installed from or uploaded to a release.
#
# Usage: tools/build-apks.sh [OUT_DIR]
#   OUT_DIR is where the APKs are left, ~/Downloads by default.
#
# Prefer the debug one on a device you test on: run-as only works on a
# debuggable app, and that is how its settings are read and edited over adb
# (see AGENTS.md). The release build is for a device that needs none of that.
#
# Both are signed with the Android debug keystore: app/build.gradle.kts has no
# signing config, and AGP falls back to that key for a release build too. It
# has to stay the same key, or a BatiDoorLink already on a device can't be
# updated, only uninstalled first.
#
# The unit tests run first, since an APK that's about to be handed out is
# worth that much. Each APK's sha256 is printed.
set -euo pipefail

if [[ $# -gt 1 ]]; then
  awk '/^# Usage:/ { on = 1 } on && /^#?$/ { exit } on { sub(/^# ?/, ""); print }' "$0" >&2
  exit 1
fi

OUT=${1:-$HOME/Downloads}
cd "$(dirname "$0")/.."

# Android Studio writes local.properties, but a plain shell has neither that
# nor, usually, ANDROID_HOME
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
[[ -d $SDK ]] || { echo "No Android SDK at $SDK; set ANDROID_HOME" >&2; exit 1; }
export ANDROID_HOME=$SDK
BUILD_TOOLS=$SDK/build-tools/$(ls -1 "$SDK/build-tools" | sort -V | tail -1)

# Gradle needs a JDK that can compile, and the ones a distribution installs
# by default are often only runtimes (on the development machine, both JDKs
# in /usr/lib/jvm lack javac). Android Studio bundles a full one.
GRADLE_ARGS=()
if [[ ! -x ${JAVA_HOME:-/nonexistent}/bin/javac ]]; then
  for jdk in "$HOME/src/android-studio/jbr" "$HOME/android-studio/jbr" /opt/android-studio/jbr \
             /snap/android-studio/current/jbr "$(dirname "$(dirname "$(readlink -f "$(command -v javac 2>/dev/null || echo /nonexistent)")")")"; do
    if [[ -x $jdk/bin/javac ]]; then
      export JAVA_HOME=$jdk
      break
    fi
  done
fi
[[ -x ${JAVA_HOME:-/nonexistent}/bin/javac ]] || { echo "No JDK with javac found; set JAVA_HOME" >&2; exit 1; }
# Otherwise Gradle's toolchain search can still pick a JDK without javac for the unit tests
GRADLE_ARGS+=("-Porg.gradle.java.installations.paths=$JAVA_HOME")

mkdir -p "$OUT"
./gradlew --console=plain "${GRADLE_ARGS[@]}" testDebugUnitTest assembleDebug assembleRelease

describe() {
  local apk=$1
  local manifest
  manifest=$("$BUILD_TOOLS/aapt2" dump xmltree --file AndroidManifest.xml "$apk")
  local version debuggable
  version=$(sed -n 's/.*versionName[^"]*"\([^"]*\)".*/\1/p' <<<"$manifest" | head -1)
  # Only a debug build has it, and it's the whole reason to prefer that one
  grep -q 'debuggable(0x0101000f)=true' <<<"$manifest" && debuggable=debuggable || debuggable=""
  printf '%-27s %5.1f MB  version %-8s %-11s sha256 %s\n' \
    "$(basename "$apk")" \
    "$(awk -v b="$(stat -c%s "$apk")" 'BEGIN { printf "%.1f", b / 1048576 }')" \
    "$version" "$debuggable" "$(sha256sum "$apk" | cut -d' ' -f1)"
}

cp app/build/outputs/apk/debug/app-debug.apk "$OUT/BatiDoorLink-debug.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT/BatiDoorLink-release.apk"

echo
describe "$OUT/BatiDoorLink-debug.apk"
describe "$OUT/BatiDoorLink-release.apk"
echo
# Worth seeing: the same key has to sign every build handed out, and a Studio
# build on another machine would use that machine's debug keystore
certs=$("$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/BatiDoorLink-debug.apk" 2>/dev/null)
echo "signed by $(sed -n 's/^Signer #1 certificate DN: //p' <<<"$certs")" \
  "($(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs"))"
echo
echo "In $OUT. Install with: adb install -r $OUT/BatiDoorLink-debug.apk"
