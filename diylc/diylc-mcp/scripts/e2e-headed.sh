#!/usr/bin/env bash
#
# MANUAL headed-mode smoke test for diylc-mcp. NOT run in CI — it needs a display.
#
# Launches the server in headed mode (real MainFrame window pops up on your display), drives it over
# stdio to place two components, and asserts that describe_project — read back through the SAME
# Presenter the live window observes — reflects them. Watch the window: the parts appear in it as the
# agent places them, and you can edit alongside.
#
# Usage:  DISPLAY=:0 bash diylc/diylc-mcp/scripts/e2e-headed.sh [path/to/diylc-mcp.jar]
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${1:-$HERE/../target/diylc-mcp.jar}"
[ -f "$JAR" ] || { echo "FAIL: jar not found at $JAR (run: mvn -pl diylc-mcp -am package)"; exit 1; }
[ -n "${DISPLAY:-}" ] || { echo "FAIL: no DISPLAY set — headed mode needs a display"; exit 1; }

OUT="$(mktemp)"
# headed flag ON; java.awt.headless deliberately NOT set.
JVM=(-Dorg.diylc.mcp.headed=true
  --add-opens java.base/java.util=ALL-UNNAMED
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.text=ALL-UNNAMED
  --add-opens java.desktop/java.awt=ALL-UNNAMED
  --add-opens java.desktop/java.awt.font=ALL-UNNAMED
  --add-opens java.desktop/java.awt.geom=ALL-UNNAMED)

CONVO=$(cat <<'JSON'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"diylc_add_component","arguments":{"type":"Resistor","points":[[60,60],[140,60]]}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"diylc_add_component","arguments":{"type":"Film Capacitor (Radial)","points":[[140,60],[140,160]]}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"diylc_describe_project","arguments":{}}}
JSON
)

# Give yourself a few seconds to look at the window before stdin closes and it exits.
{ echo "$CONVO"; sleep 6; } | timeout 90 java "${JVM[@]}" -jar "$JAR" 2>/dev/null > "$OUT" || true

python3 - "$OUT" <<'PY'
import json, sys
ok = False
for line in open(sys.argv[1]):
    line = line.strip()
    if not line:
        continue
    m = json.loads(line)
    if m.get("id") == 4:
        d = json.loads(m["result"]["content"][0]["text"])
        names = [c["name"] for c in d["components"]]
        print("headed describe -> componentCount=%d %s" % (d["componentCount"], names))
        ok = d["componentCount"] == 2
print("RESULT:", "PASSED" if ok else "FAILED")
sys.exit(0 if ok else 1)
PY
