#!/bin/bash
# Extracts a `*-sources.jar` from the Gradle dependency cache into
# /tmp/acp-sdk-sources/<artifact>/ so the sources can be inspected with
# grep/read tools. Idempotent: an existing extraction is reused.
#
# Usage: sdk-sources.sh <coordinate-substring> [version]
# e.g. sdk-sources.sh kotlin-sdk-core        (picks the newest version)
#      sdk-sources.sh acp-model 0.30.1
set -euo pipefail
pattern=${1:?usage: sdk-sources.sh <coordinate-substring> [version]}
version=${2:-}
cache=${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}
dest_root=/tmp/acp-sdk-sources
jar=$(find "$cache" -path "*${pattern}*/${version:+$version/}*" -name "*-sources.jar" 2>/dev/null \
  | sort -V | tail -1)
if [ -z "$jar" ]; then
  echo "no *-sources.jar matching '$pattern' ${version:+($version)} under $cache" >&2
  echo "hint: run ./gradlew build once so the sources are resolved" >&2
  exit 1
fi
artifact=$(basename "$jar" -sources.jar)
dest="$dest_root/$artifact"
if [ -d "$dest" ] && [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then
  echo "already extracted: $dest"
  exit 0
fi
rm -rf "$dest"
mkdir -p "$dest"
unzip -o -q "$jar" -d "$dest"
echo "extracted $jar -> $dest"
echo "sources root(s): $(find "$dest" -mindepth 1 -maxdepth 1 -type d -printf '%f ' )"