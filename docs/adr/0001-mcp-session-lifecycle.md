# MCP session lifecycle is explicit and decoupled from the process

The `diylc-mcp` server boots with **no session**. `diylc_start_session` opens an editing context (a
`Presenter`, its in-memory project, and — when `headed=true` — a live `MainFrame`); `diylc_end_session`
tears it down. At most one session exists at a time, and a session can be re-opened after closing.
Every other tool requires an open session and returns an error otherwise.

This **reverses** the earlier tied-lifecycle headed mode (ADR-less, PR #2), where headed/headless was a
launch flag and File→Exit / stdin-EOF ended the whole process. We chose the inversion so the GUI can be
opened and closed on demand mid-conversation, and so a human closing the window does not kill the agent's
server.

## Consequences

- **Decoupled lifecycles.** `end_session` (and the human closing the window) ends the *session*, not the
  process. Only MCP-client disconnect (stdin EOF) ends the process.
- **`MainFrame.System.exit` is suppressed.** `MainFrame` and `ExitAction` hard-call `System.exit(0)` on
  close. A static `MainFrame.exitHandler` hook now routes those through the server, which ends the
  session instead. This is a surgical change to a shared GUI class — surprising without this context.
- **Headed needs a live display.** Because AWT headlessness is fixed at JVM start, the server must launch
  **without** `-Djava.awt.headless=true`; `start_session(headed=true)` errors when no display is present.
- **No auto-start.** Existing clients that expected tools to work immediately must now call
  `diylc_start_session` first (breaking change).

## Considered alternatives

- *Keep the tied lifecycle (PR #2).* Simpler, but the GUI can't be re-opened and a human window-close
  kills the agent's server.
- *Sessions gate only the GUI; headless tools always work.* Rejected — contradicts the requirement that
  every tool errors without an open session.
