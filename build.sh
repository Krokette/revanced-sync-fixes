#!/usr/bin/env bash
# Builds build/patches.rvp, the patch bundle for ReVanced Manager and revanced-cli, and runs the tests.
#
# Needs only a JDK 17 or newer (`java` on PATH, or JAVA_HOME) and curl. The compilers come from their official
# releases, pinned and checked by SHA-256, into .tools/ on the first run:
#   - the patches are compiled with kotlinc against revanced-cli's patcher (the API of ReVanced Patches 6.x),
#   - the extension (code that runs inside Sync) with javac against org.json, which Android provides,
#   - both are dexed with Google's d8, because ReVanced Manager loads classes.dex.
set -euo pipefail

cd "$(dirname "$0")"
VERSION=$(tr -d '[:space:]' < VERSION)
TOOLS=.tools
OUT=build

CLI_VERSION=6.0.0
KOTLIN_VERSION=2.3.21
R8_VERSION=8.13.25
JSON_VERSION=20250517

CLI=$TOOLS/revanced-cli-$CLI_VERSION-all.jar
KOTLIN=$TOOLS/kotlin-compiler-$KOTLIN_VERSION.zip
R8=$TOOLS/r8-$R8_VERSION.jar
JSON=$TOOLS/json-$JSON_VERSION.jar

if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
# javac and jar through `java`, so only one JDK binary has to be on PATH.
javac() { "$JAVA" --module jdk.compiler/com.sun.tools.javac.Main "$@"; }
jar() { "$JAVA" --module jdk.jartool/sun.tools.jar.Main "$@"; }
d8() { "$JAVA" -cp "$R8" com.android.tools.r8.D8 "$@"; }

# Class paths: ':' everywhere but Windows (Git Bash), which wants ';'.
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac

sha256() {
  if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

fetch() { # url sha256 file
  if [ -f "$3" ] && [ "$(sha256 "$3")" = "$2" ]; then return; fi
  echo "Downloading $(basename "$3")"
  mkdir -p "$TOOLS"
  curl -fsSL -o "$3.part" "$1"
  if [ "$(sha256 "$3.part")" != "$2" ]; then
    rm -f "$3.part"
    echo "Checksum mismatch for $(basename "$3")" >&2
    exit 1
  fi
  mv "$3.part" "$3"
}

fetch "https://github.com/ReVanced/revanced-cli/releases/download/v$CLI_VERSION/revanced-cli-$CLI_VERSION-all.jar" \
  c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958 "$CLI"
fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" \
  a8cfc1d62cd4d0de4d04f42575e40135bd620588c17d568a20eb9c7c259af14f "$KOTLIN"
fetch "https://dl.google.com/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar" \
  0d4f2f68c4fcbc9434eff122f41feb6fb1d98efd2faa530cec1abacab1cea03d "$R8"
fetch "https://repo1.maven.org/maven2/org/json/json/$JSON_VERSION/json-$JSON_VERSION.jar" \
  3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796 "$JSON"
# Unpacked next to a marker holding the zip's checksum: a new version, or an unpack that didn't finish, unpacks again.
KOTLINC=$TOOLS/kotlinc-$KOTLIN_VERSION
KOTLIN_SHA=$(sha256 "$KOTLIN")
if [ "$(cat "$KOTLINC/.unpacked" 2> /dev/null)" != "$KOTLIN_SHA" ]; then
  rm -rf "$KOTLINC" "$TOOLS/kotlinc"
  (cd "$TOOLS" && "$JAVA" --module jdk.jartool/sun.tools.jar.Main xf "kotlin-compiler-$KOTLIN_VERSION.zip")
  mv "$TOOLS/kotlinc" "$KOTLINC"
  echo "$KOTLIN_SHA" > "$KOTLINC/.unpacked"
fi

rm -rf "$OUT"
mkdir -p "$OUT/extension" "$OUT/patches" "$OUT/test" "$OUT/rvp/extensions" "$OUT/rvp/META-INF"

echo "== Extension"
javac --release 11 -nowarn -cp "$JSON" -d "$OUT/extension" $(find extension -name '*.java')
d8 --release --min-api 23 --lib "$JSON" --output "$OUT/rvp/extensions" $(find "$OUT/extension" -name '*.class')
mv "$OUT/rvp/extensions/classes.dex" "$OUT/rvp/extensions/syncfixes.rve"

echo "== Tests"
javac -nowarn -cp "$JSON$SEP$OUT/extension" -d "$OUT/test" test/*.java
"$JAVA" -cp "$JSON$SEP$OUT/extension$SEP$OUT/test" SyncFixesTest

echo "== Patches"
# The patcher was built with a pre-release Kotlin; its API is the one the official patches use.
"$JAVA" -cp "$KOTLINC/lib/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -nowarn -Xskip-prerelease-check \
  -cp "$CLI" -d "$OUT/patches" $(find patches -name '*.kt')
cp -r "$OUT/patches/." "$OUT/rvp/"
# The desktop CLI loads the .class files, ReVanced Manager this classes.dex.
d8 --release --min-api 26 --classpath "$CLI" --output "$OUT/rvp" $(find "$OUT/patches" -name '*.class')

cat > "$OUT/MANIFEST.MF" << EOF
Manifest-Version: 1.0
Name: Sync fixes
Description: Fixes galleries and load more comments in Sync for Reddit v23.06.30-13:39
Version: $VERSION
Source: https://github.com/Krokette/revanced-sync-fixes
Author: Krokette
Contact: https://github.com/Krokette/revanced-sync-fixes/issues
Website: https://github.com/Krokette/revanced-sync-fixes
License: GNU General Public License v3.0
EOF
jar --create --file "$OUT/patches.rvp" --manifest "$OUT/MANIFEST.MF" -C "$OUT/rvp" .
echo "Built $OUT/patches.rvp (v$VERSION)"
