# diylc-mcp — DIYLC Model Context Protocol server

A [Model Context Protocol](https://modelcontextprotocol.io) server that exposes a **headless DIYLC
engine** to AI agents, so an agent can open, inspect, edit, render, and save `.diy` projects through
tool calls.

It embeds the real `diylc-core` controller (`Presenter` + a no-op `DummyView`) — the exact same
engine the Swing app drives — so grid snapping, control-point/node resolution, netlist analysis,
serialization, and rendering behave identically to the desktop application. No GUI is created.

## Build

From the `diylc/` directory:

```bash
mvn -pl diylc-mcp -am package    # produces diylc-mcp/target/diylc-mcp.jar (runnable, deps bundled)
```

## Run

The server speaks JSON-RPC 2.0 over **stdio** (newline-delimited frames). It needs the same
`--add-opens` flags the desktop app uses (DIYLC reflects into `java.desktop`/`java.base`):

```bash
java -Djava.awt.headless=true \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.text=ALL-UNNAMED \
  --add-opens java.desktop/java.awt=ALL-UNNAMED \
  --add-opens java.desktop/java.awt.font=ALL-UNNAMED \
  --add-opens java.desktop/java.awt.geom=ALL-UNNAMED \
  -jar diylc-mcp/target/diylc-mcp.jar
```

### MCP client config (e.g. Claude Desktop / Claude Code)

```json
{
  "mcpServers": {
    "diylc": {
      "command": "java",
      "args": [
        "-Djava.awt.headless=true",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.text=ALL-UNNAMED",
        "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens", "java.desktop/java.awt.geom=ALL-UNNAMED",
        "-jar", "/abs/path/to/diylc/diylc-mcp/target/diylc-mcp.jar"
      ]
    }
  }
}
```

## Tools

One long-lived project (the "session") is held in memory across calls.

| Tool | Purpose |
|------|---------|
| `diylc_new_project` | Start an empty project |
| `diylc_open_project` | Load a `.diy` file |
| `diylc_save_project` | Write the project to a `.diy` file |
| `diylc_describe_project` | Structured dump: metadata + components (type, name, value, control points with node names) |
| `diylc_list_component_types` | Available component types by category (optionally filtered) |
| `diylc_get_netlist` | Textual netlist(s); `includeSwitches` expands switch positions |
| `diylc_render_png` | Render the layout to a PNG image block for visual inspection |
| `diylc_add_component` | Place a component — one point for single-click types, two+ for point-by-point (resistors, wires) |
| `diylc_set_property` | Set a property by editor name (`Value`, `Color`, …) on the selection; parses typed values (measures, enums) |
| `diylc_delete_selection` | Delete selected components |
| `diylc_get_selection` / `diylc_select_all` / `diylc_select_matching` | Selection management |
| `diylc_group_selection` / `diylc_ungroup_selection` | Grouping |
| `diylc_rotate_selection` / `diylc_mirror_selection` | Transforms |

### Coordinates

Coordinates are project pixels. The server disables the canvas extra-space margin and pins zoom to
1.0, so the `[x,y]` you pass to `diylc_add_component` is exactly where a control point lands (e.g.
`[[60,60],[140,60]]` → a horizontal resistor 80px wide). Default grid is `0.1in`; placement snaps to
it.

## Architecture

```
agent ──stdio JSON-RPC──▶ McpServer ──▶ ToolRegistry ──▶ DiylcEngine ──▶ Presenter (headless)
                                                                            └─ diylc-core / diylc-library
```

- **`McpServer`** — the stdio JSON-RPC loop: `initialize`, `tools/list`, `tools/call`. Captures the
  real stdout for protocol frames and redirects `System.out` to stderr so DIYLC's logging never
  corrupts the channel.
- **`ToolRegistry`** — declares each `Tool` (name, JSON-Schema, handler) bound to one engine.
- **`DiylcEngine`** — the headless `Presenter` wrapper and session state. Edit operations reuse the
  same slot + click flow as the GUI (`setNewComponentTypeSlot` → `mouseMoved`/`mouseClicked`), so
  behaviour matches the app exactly.

### Extending toward full GUI parity

Every public `Presenter` verb can become a tool. To add one:

1. Add a method to `DiylcEngine` wrapping the `Presenter` call (convert/serialize as needed).
2. Register it in `ToolRegistry` with a `reg(name, description, schema, handler)` call.

No changes to `McpServer` are needed. The current set is a representative slice across read / edit /
parity categories; the registry is the single extension point.

### Limitations / TODO

- **Multi-point components** beyond two control points: `diylc_add_component` clicks each supplied
  point, but components needing intermediate drags are untested.
- **`diylc_set_property`** parses String/boolean/number/enum/measure types. Compound or
  object-valued properties (e.g. custom editors) are not yet handled.
- **Undo/redo, building blocks, variants, export (PDF/Gerber/image-file), layers** are not yet
  exposed — they are mechanical additions following the pattern above.
- A single in-memory session; no multi-document or concurrent-request support.
