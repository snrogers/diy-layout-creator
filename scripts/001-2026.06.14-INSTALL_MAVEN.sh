#!/usr/bin/env bash
# Install Apache Maven 3.9.9 to ~/.local/maven (the repo build needs mvn; some envs lack it).
# Usage: bash scripts/001-2026.06.14-INSTALL_MAVEN.sh ; then add to PATH:
#   export PATH="$HOME/.local/maven/apache-maven-3.9.9/bin:$PATH"
set -euo pipefail

VERSION=3.9.9
DEST="$HOME/.local/maven"
URL="https://archive.apache.org/dist/maven/maven-3/${VERSION}/binaries/apache-maven-${VERSION}-bin.tar.gz"

mkdir -p "$DEST"
tmp="$(mktemp -d)"
curl -fsSL -o "$tmp/maven.tar.gz" "$URL"
tar xzf "$tmp/maven.tar.gz" -C "$DEST"
rm -rf "$tmp"

echo "Installed: $DEST/apache-maven-${VERSION}/bin/mvn"
"$DEST/apache-maven-${VERSION}/bin/mvn" -v
