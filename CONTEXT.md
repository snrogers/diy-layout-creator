# DIY Layout Creator

Domain glossary for DIYLC. Terms here are the canonical vocabulary; prefer them over the listed aliases.

## Language

### diylc-mcp (MCP server)

**Session**:
A live editing context owned by the MCP server: one `Presenter` (with its in-memory project) and, when headed, one live `MainFrame` window. At most one exists at a time; opened by `startSession`, torn down by `endSession`. Every other tool requires an open Session.
_Avoid_: connection, context (overloaded), instance

**Headed Session**:
A Session created with `headed=true` — it owns a visible `MainFrame` the human can co-edit. A headless Session has no window.
_Avoid_: GUI mode, windowed mode

**Open / Close a Session**:
`diylc_start_session` opens a Session; `diylc_end_session` closes it. These are explicit and runtime-driven — the server boots with no Session and never auto-opens one. Closing a Session does not stop the server; another can be opened afterward.
_Avoid_: connect/disconnect, launch/quit (those imply the process)

**Force-close**:
`diylc_end_session(force=true)` discards a modified project and closes anyway. Without `force`, closing a Session with unsaved changes is refused.

## Relationships

- The **MCP server** process hosts **at most one Session at a time**, and **zero** between `end_session` and the next `start_session`
- A **Session** owns exactly one `Presenter`; a **Headed Session** also owns one `MainFrame`
- Closing the **MainFrame** window (human) ends the **Session**, not the server process; only MCP-client disconnect (stdin EOF) ends the process
- Every tool other than `diylc_start_session` requires an open Session, and `diylc_start_session` requires that none is open

## Example dialogue

> **Dev:** "If the human clicks the window's X, does the MCP server die?"
> **Domain expert:** "No — that ends the **Session** but the server lives, so the agent can `start_session` again. Only the client disconnecting kills the process."
> **Dev:** "And `start_session(headed=true)` with no display?"
> **Domain expert:** "Error. headless Sessions run anywhere; a **Headed Session** needs a real display."

## Flagged ambiguities

- "session" initially conflated the editing context with the server process — resolved: a **Session** is opened/closed at runtime and is distinct from the process lifetime.
