#!/usr/bin/env bash
#
# Deploy the diylc-mcp server to a stable location and (re)register it globally.
#
# Builds the runnable fat jar, copies it to ~/.local/share/diylc-mcp/diylc-mcp.jar (stable —
# survives `mvn clean` in the source tree), and registers it as a user-scope (global) MCP server so
# every Claude Code project gets the mcp__diylc__* tools. Idempotent: safe to re-run after a rebuild.
#
# Usage:  bash scripts/002-2026.06.16-DEPLOY_DIYLC_MCP.sh [--skip-build]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIYLC="$REPO_ROOT/diylc"
DEST_DIR="$HOME/.local/share/diylc-mcp"
JAR_NAME="diylc-mcp.jar"
DEST_JAR="$DEST_DIR/$JAR_NAME"
SERVER_NAME="diylc"

# --- 1. build (unless --skip-build) -------------------------------------------------------------
if [ "${1:-}" != "--skip-build" ]; then
  MVN="${MVN:-mvn}"
  if ! command -v "$MVN" >/dev/null 2>&1; then
    MVN="$HOME/.local/maven/apache-maven-3.9.9/bin/mvn"
  fi
  echo ">> building diylc-mcp jar"
  ( cd "$DIYLC" && "$MVN" -q -pl diylc-mcp -am package -DskipTests )
fi

SRC_JAR="$DIYLC/diylc-mcp/target/diylc-mcp.jar"
[ -f "$SRC_JAR" ] || { echo "FAIL: built jar not found at $SRC_JAR"; exit 1; }

# --- 2. copy to stable location -----------------------------------------------------------------
mkdir -p "$DEST_DIR"
cp -f "$SRC_JAR" "$DEST_JAR"
echo ">> deployed jar -> $DEST_JAR ($(du -h "$DEST_JAR" | cut -f1))"

# --- 3. (re)register as a global (user-scope) MCP server ---------------------------------------
# java.awt.headless is deliberately NOT set, so diylc_start_session(headed=true) can open a window.
ARGS=(
  --add-opens java.base/java.util=ALL-UNNAMED
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.text=ALL-UNNAMED
  --add-opens java.desktop/java.awt=ALL-UNNAMED
  --add-opens java.desktop/java.awt.font=ALL-UNNAMED
  --add-opens java.desktop/java.awt.geom=ALL-UNNAMED
  -jar "$DEST_JAR"
)

# Remove any prior registration at any scope, then add fresh at user scope.
for scope in local user; do
  claude mcp remove "$SERVER_NAME" -s "$scope" >/dev/null 2>&1 || true
done
claude mcp add "$SERVER_NAME" -s user -- java "${ARGS[@]}" >/dev/null
echo ">> registered '$SERVER_NAME' at user scope (all projects)"

# --- 4. verify ----------------------------------------------------------------------------------
echo
claude mcp get "$SERVER_NAME" | sed -n '1,6p'
echo
echo "Done. Tools load at session start in any Claude Code session; call diylc_start_session first."
