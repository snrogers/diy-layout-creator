# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

DIY Layout Creator (DIYLC) is a cross-platform Java/Swing desktop app for drawing circuit layouts (stripboard, perfboard, PCB, point-to-point, guitar wiring). It exports to image/PDF/Gerber/SPICE and analyzes guitar wirings and netlists.

## Build & Run

All Maven commands run from the `diylc/` directory (multi-module reactor; parent `org.diylc:diylc-parent`).

```bash
cd diylc
mvn -B test                       # build + run all unit tests (this is what CI runs)
mvn -B package                    # build shaded runnable jar -> diylc-swing/target/diylc.jar
mvn -pl diylc-core -am test       # build/test a single module (-am = also build deps)
mvn -pl diylc-library test -Dtest=LeverSwitchTests   # run a single test class
```

- **JDK 17** required (`maven.compiler.source/target=17`). Source encoding is **ISO-8859-1** — preserve it; do not let editors rewrite files as UTF-8.
- Main class: `org.diylc.DIYLCStarter` (module `diylc-swing`). The app needs a slew of `--add-opens`/`--add-exports` JVM flags (see `diylc/.run/DIYLCStarter.run.xml`) because it reflects into `java.desktop` and `java.base`. Run via the IntelliJ run configs in `diylc/.run/` rather than reconstructing the flags by hand.
- Several deps are bundled jars under `diylc/lib/` and `diylc-swing/tools/` referenced via `<systemPath>` (svgSalamander, jep, gerber-writer, swing/app-framework). They are not in Maven Central.

## Module Architecture

Four modules. The app is `core <- library <- swing`; `diylc-mcp` is a separate headless front-end depending on `core`+`library`:

- **diylc-core** — engine, no Swing UI. Key packages:
  - `core/` & `common/` — domain interfaces. `IDIYComponent` is the central abstraction; `Project` is the document model; `IPlugInPort` is the facade the UI and plugins talk to (it extends many `I*Processor` interfaces — selection, mouse, keyboard, netlist, variants, blocks).
  - `presenter/` — `Presenter` is the core controller implementing `IPlugInPort`; mediates between the `Project` model and views. Also rendering helpers (`DrawingManager`, `G2DWrapper`, `DrawingCache`), `ComponentProcessor`/`InstantiationManager` (reflection-driven component creation), netlist/continuity analysis.
  - `netlist/`, `serialization/` (XStream-based `.diy` files), `lang/` (i18n via `LangUtil`), `plugins/` (chatbot, cloud, compare).
- **diylc-library** — the actual component catalog under `components/` (passive, semiconductors, tube, guitar, boards, electromechanical, smd, …). ~148 components are registered declaratively via the `@ComponentDescriptor` annotation; they extend `AbstractComponent<T>` (in core) and its subclasses (`AbstractLeadedComponent`, `AbstractTransparentComponent`, etc.). Adding a component = new annotated class here, no central registry edit.
- **diylc-swing** — Swing UI. `DIYLCStarter` boots it; `swing/gui/MainFrame`, `ActionFactory`, and `swing/plugins/` provide the UI. `ISwingUI`/`IView` connect UI to the core presenter.
- **diylc-mcp** — a [Model Context Protocol](https://modelcontextprotocol.io) server (`org.diylc.mcp.McpServer`) exposing the engine to AI agents over stdio JSON-RPC. Tools are declared in `ToolRegistry`; `DiylcEngine` wraps the `Presenter`, reusing the GUI's slot+click flow for edits so behaviour matches the app. **Explicit sessions** (ADR-0001): the server boots session-less; `diylc_start_session` (`headed` bool) opens a **headless** session (`DummyView`, no GUI) or a **headed** one (live `MainFrame` for human+agent co-editing, each tool call on the Swing EDT via `EdtExecutor`); `diylc_end_session` tears it down without stopping the server; every other tool errors until a session is open. `SessionManager` owns the single session. Depends on `core`+`library`+`swing`. See `diylc/diylc-mcp/README.md`. Build: `mvn -pl diylc-mcp -am package` → runnable `diylc-mcp/target/diylc-mcp.jar`.

The plugin model: the UI and features register against `IPlugInPort`/`Presenter` and communicate through `EventType` messages rather than direct calls.

## Testing

- **Unit tests** — JUnit 4, standard `src/test/java` per module. Netlist/switch logic has heavy coverage in `diylc-library` (e.g. `LeverSwitchTests`, `GuitarDiagramAnalyzerTests`).
- **Regression tests** — `org.diylc.RegressionTestRunner` (in `diylc-swing/src/test`) renders fixture `.diy` projects and diffs output against golden reports. Data lives in `diylc-regression-data/` (`input/`, `reports/`). Invoke as `RegressionTestRunner {path-to-diylc-regression-data} PREPARE|TEST [filter]`: `PREPARE` regenerates golden output, `TEST` verifies against it. Use the `diylc/.run/RegressionTestRunner.run.xml` config.

## Other directories

- `diylc-server-api/v1/` — PHP backend for the optional cloud sharing feature (separate from the Java app).
- `knowledge/` — SQL/data dumps (component summaries, version history) feeding the in-app chatbot/datasheet features; not part of the build.

## CI

`.github/workflows/tests.yml` runs `cd diylc && mvn -B test` on push/PR to `master`. Keep that green.

## Agent skills

### Issue tracker

Issues and PRDs live as local markdown under `.scratch/<feature>/` (no remote tracker). See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles with default names, recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
