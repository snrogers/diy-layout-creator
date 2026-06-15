# diylc-mcp — DIYLC Model Context Protocol server

A [Model Context Protocol](https://modelcontextprotocol.io) server that exposes a **DIYLC engine** to
AI agents, so an agent can open, inspect, edit, render, and save `.diy` projects through tool calls.

It embeds the real `diylc-core` controller (`Presenter`) — the exact same engine the Swing app
drives — so grid snapping, control-point/node resolution, netlist analysis, serialization, and
rendering behave identically to the desktop application.

Two modes:
- **Headless** (default) — `Presenter` over a no-op `DummyView`; no GUI. For agent/CI runs.
- **Headed** — drives the `Presenter` of a live `MainFrame` so a human and the agent **co-edit one
  shared project**: tool calls update the real window in real time, and the human's edits show up in
  the agent's next `describe_project`. See [Headed mode](#headed-mode--co-editing).

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

## Headed mode — co-editing

Headed mode brings up the real DIYLC `MainFrame` and points the MCP tools at **its** `Presenter`, so
the agent and a human edit one shared project live. Activate it with `-Dorg.diylc.mcp.headed=true`
(or `DIYLC_MCP_HEADED=1`), and **omit** `-Djava.awt.headless=true` — a display is required:

```bash
java -Dorg.diylc.mcp.headed=true \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.text=ALL-UNNAMED \
  --add-opens java.desktop/java.awt=ALL-UNNAMED \
  --add-opens java.desktop/java.awt.font=ALL-UNNAMED \
  --add-opens java.desktop/java.awt.geom=ALL-UNNAMED \
  -jar diylc-mcp/target/diylc-mcp.jar
```

How it works:
- **Shared Presenter.** The engine drives `mainFrame.getPresenter()`; agent edits dispatch the same
  repaint events the GUI observes, so the window updates as the agent works, and `describe_project`
  reflects the human's edits too.
- **EDT threading.** Each tool call runs in full on the Swing Event Dispatch Thread via
  `invokeAndWait`, so it is atomic against the human's UI events and never touches the `Presenter`
  off-thread.
- **Coordinates.** Tool coordinates are project pixels; placement converts to canvas pixels using the
  Presenter's live extra-space + zoom (`Coordinates.toCanvas`), so it lands correctly no matter how
  the human has zoomed/panned — and no view config is mutated.
- **Lifecycle.** One process: File→Exit (human) ends the server; MCP client disconnect (stdin EOF)
  shuts down the JVM and closes the window.

Caveats (by design):
- Agent edit tools operate on the shared selection — running `select_all`/`set_property` **clobbers
  the human's current selection or armed component**.
- It is the real app: AutoSave writes `~/diylc/backup`, and cloud/chatbot menus are present.
- The agent is **not** pushed the human's changes; it sees them only on its next read tool.

If headed is requested with no display (`java.awt.headless`), the server exits with a clear message.

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

Coordinates are project pixels. Placement converts them to canvas pixels per call via
`Coordinates.toCanvas` using the Presenter's live extra-space margin and zoom (the inverse of
`Presenter.scalePoint`), so the `[x,y]` you pass to `diylc_add_component` lands at that project point
regardless of view state — e.g. `[[60,60],[140,60]]` → a horizontal resistor 80px wide. Default grid
is `0.1in`; placement snaps to it.

## Architecture

```
agent ─stdio JSON-RPC→ McpServer ─(EdtExecutor)→ ToolRegistry → DiylcEngine → Presenter
                                                                                ├─ DummyView        (headless)
                                                                                └─ live MainFrame   (headed)
```

- **`McpServer`** — the stdio JSON-RPC loop: `initialize`, `tools/list`, `tools/call`. Captures the
  real stdout for protocol frames and redirects `System.out` to stderr so DIYLC's logging never
  corrupts the channel. Reads the headed flag at boot; in headed mode it builds the `MainFrame` on the
  EDT and ties JVM lifecycle to stdin.
- **`EdtExecutor`** — runs each tool call on the Swing EDT (headed) or inline (headless).
- **`ToolRegistry`** — declares each `Tool` (name, JSON-Schema, handler) bound to one engine.
- **`DiylcEngine`** — the `Presenter` wrapper and session state (own `DummyView` Presenter when
  headless; a live frame's Presenter via `forPresenter` when headed). Edit operations reuse the same
  slot + click flow as the GUI (`setNewComponentTypeSlot` → `mouseMoved`/`mouseClicked`), so behaviour
  matches the app exactly.
- **`Coordinates`** — pure project↔canvas math, unit-tested without a display.

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
- A single session; no multi-document or concurrent-request support.
- **Headed mode** co-editing caveats (selection clobbering, no push of human edits to the agent,
  real-app side effects) are listed under [Headed mode](#headed-mode--co-editing).
