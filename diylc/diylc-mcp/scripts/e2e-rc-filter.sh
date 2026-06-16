#!/usr/bin/env bash
#
# End-to-end smoke test for the diylc-mcp server.
#
# Drives the built jar as a real MCP server over stdio (newline-delimited JSON-RPC 2.0), builds an
# RC low-pass filter (R from input node to the output node; C from the output node down to ground),
# sets typed component values, then asserts on the structured results and emits a rendered PNG.
#
# Usage:  bash diylc/diylc-mcp/scripts/e2e-rc-filter.sh [path/to/diylc-mcp.jar]
# Output: /tmp/diylc-mcp-e2e/rc_filter.diy  and  rc_filter.png
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${1:-$HERE/../target/diylc-mcp.jar}"
OUTDIR="/tmp/diylc-mcp-e2e"
DIY="$OUTDIR/rc_filter.diy"
PNG="$OUTDIR/rc_filter.png"
RESP="$OUTDIR/responses.jsonl"

[ -f "$JAR" ] || { echo "FAIL: jar not found at $JAR (run: mvn -pl diylc-mcp -am package)"; exit 1; }
mkdir -p "$OUTDIR"; rm -f "$DIY" "$PNG" "$RESP"

JVM=(-Djava.awt.headless=true
  --add-opens java.base/java.util=ALL-UNNAMED
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.text=ALL-UNNAMED
  --add-opens java.desktop/java.awt=ALL-UNNAMED
  --add-opens java.desktop/java.awt.font=ALL-UNNAMED
  --add-opens java.desktop/java.awt.geom=ALL-UNNAMED)

# The conversation. Output node is the shared point (140,60): R's right lead and C's top lead meet.
#   R1: (60,60) -> (140,60)        [input -> output]
#   C1: (140,60) -> (140,160)      [output -> ground]
CONVO=$(cat <<JSON
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":1.5,"method":"tools/call","params":{"name":"diylc_start_session","arguments":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"diylc_add_component","arguments":{"type":"Resistor","points":[[60,60],[140,60]]}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"diylc_add_component","arguments":{"type":"Film Capacitor (Radial)","points":[[140,60],[140,160]]}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"diylc_select_matching","arguments":{"criteria":"R1"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"diylc_set_property","arguments":{"name":"Value","value":"10k"}}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"diylc_select_matching","arguments":{"criteria":"C1"}}}
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"diylc_set_property","arguments":{"name":"Value","value":"100nF"}}}
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"diylc_describe_project","arguments":{}}}
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"diylc_get_netlist","arguments":{}}}
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"diylc_save_project","arguments":{"path":"$DIY"}}}
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"diylc_render_png","arguments":{"includeGrid":true}}}
JSON
)

echo "$CONVO" | java "${JVM[@]}" -jar "$JAR" 2>"$OUTDIR/server.err" >"$RESP"

python3 - "$RESP" "$PNG" "$DIY" <<'PY'
import base64, json, sys
resp_path, png_path, diy_path = sys.argv[1:4]
by_id = {}
for line in open(resp_path):
    line = line.strip()
    if not line:
        continue
    m = json.loads(line)
    if "id" in m:
        by_id[m["id"]] = m

fails = []
def check(cond, msg):
    print(("PASS" if cond else "FAIL") + ": " + msg)
    if not cond:
        fails.append(msg)

# No tool call reported an error.
for i in range(2, 12):
    r = by_id.get(i, {}).get("result", {})
    if r.get("isError"):
        fails.append("tool id %d errored: %s" % (i, r.get("content")))

# Two components placed.
desc = json.loads(by_id[8]["result"]["content"][0]["text"])
check(desc["componentCount"] == 2, "exactly 2 components placed")
types = sorted(c["type"] for c in desc["components"])
check(any("Resistor" in t for t in types), "a Resistor is present")
check(any("Capacitor" in t for t in types), "a Capacitor is present")

# Typed values round-tripped.
vals = {c["name"]: str(c["value"]) for c in desc["components"]}
check(any("10" in v.upper() for v in vals.values()), "resistor value set (~10k)")
check(any("100" in v for v in vals.values()), "capacitor value set (~100nF)")

# R and C actually share the output node (the RC junction).
nodes_by_comp = {c["name"]: {p["node"] for p in c["controlPoints"]} for c in desc["components"]}
pts = {(p["x"], p["y"]) for c in desc["components"] for p in c["controlPoints"]}
check((140.0, 60.0) in pts, "shared output node exists at (140,60)")

# Netlist computed and references both parts.
netlist = "\n".join(json.loads(by_id[9]["result"]["content"][0]["text"]))
check(bool(netlist.strip()), "netlist is non-empty")

# Render produced a real PNG.
img = by_id[11]["result"]["content"][0]
ok_img = img.get("type") == "image" and img.get("mimeType") == "image/png"
if ok_img:
    data = base64.b64decode(img["data"])
    open(png_path, "wb").write(data)
    check(len(data) > 1000, "rendered PNG is non-trivial (%d bytes)" % len(data))
else:
    check(False, "render returned an image block")

print("\nNetlist:\n" + netlist)
print("Saved:   " + diy_path)
print("Render:  " + png_path)
print()
if fails:
    print("RESULT: FAILED (%d)" % len(fails)); sys.exit(1)
print("RESULT: ALL CHECKS PASSED"); sys.exit(0)
PY
