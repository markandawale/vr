#!/usr/bin/env sh
# Lightweight Gradle bootstrapper for this source bundle (Gradle 8.13).
set -eu
VERSION=8.13
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/pano-play-wrapper"
DIST="$BASE/gradle-$VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  TMP="$BASE/gradle-$VERSION-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$TMP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP" "$URL"
  else
    echo "Install curl or wget to download Gradle $VERSION." >&2
    exit 1
  fi
  unzip -q -o "$TMP" -d "$BASE"
  rm -f "$TMP"
  # Keep the extracted distribution in the expected fixed location.
  [ -d "$BASE/gradle-$VERSION" ] || { echo "Gradle archive did not contain expected folder." >&2; exit 1; }
  DIST="$BASE/gradle-$VERSION"
fi
exec "$DIST/bin/gradle" "$@"
