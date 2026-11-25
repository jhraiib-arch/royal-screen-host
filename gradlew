#!/bin/sh
set -e

GRADLE_VERSION="8.7"
GRADLE_ZIP_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_HOME_DIR="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"

if [ ! -d "$GRADLE_HOME_DIR" ]; then
  mkdir -p "$GRADLE_HOME_DIR"
  curl -fsSL "$GRADLE_ZIP_URL" -o /tmp/gradle.zip
  unzip -qo /tmp/gradle.zip -d "$GRADLE_HOME_DIR"
  rm -f /tmp/gradle.zip
fi

GRADLE_BIN=$(find "$GRADLE_HOME_DIR" -maxdepth 3 -type f -name "gradle" | head -n 1)
exec "$GRADLE_BIN" "$@"
