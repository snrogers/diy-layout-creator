/*
 * DIY Layout Creator (DIYLC). Copyright (c) 2009-2025 held jointly by the individual authors.
 *
 * This file is part of DIYLC. DIYLC is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * DIYLC is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General Public
 * License along with DIYLC. If not, see <http://www.gnu.org/licenses/>.
 */
package org.diylc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and holds every {@link Tool} the server exposes, all bound to one {@link SessionManager}.
 *
 * <p>{@code diylc_start_session} / {@code diylc_end_session} manage the session; every other tool
 * reaches the engine through {@link #engine()} (i.e. {@link SessionManager#requireEngine()}), which
 * errors when no session is open. Tools are grouped into session, read, edit, and "GUI parity"
 * sections; extending toward full parity is mechanical — add a {@code reg(...)} call wrapping the
 * corresponding {@link org.diylc.presenter.Presenter} verb. See diylc-mcp/README.md.
 */
public class ToolRegistry {

  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private final SessionManager sessions;

  public ToolRegistry(SessionManager sessions) {
    this.sessions = sessions;
    registerSessionTools();
    registerReadTools();
    registerEditTools();
    registerParityTools();
  }

  /** The engine for the open session; errors (reported to the agent) if no session is open. */
  private DiylcEngine engine() {
    return sessions.requireEngine();
  }

  public List<Tool> all() {
    return List.copyOf(tools.values());
  }

  public Tool get(String name) {
    return tools.get(name);
  }

  // --- registration ------------------------------------------------------------------------------

  private void registerSessionTools() {
    reg("diylc_start_session",
        "Open a DIYLC editing session. headed=true opens a live app window for human co-editing (needs "
            + "a display); headed=false (default) is headless. Must be called before any other tool. "
            + "Errors if a session is already open.",
        objectSchema(Map.of("headed", boolProp("Open a live GUI window for co-editing (default false)")), List.of()),
        args -> sessions.startSession(optBool(args, "headed", false)));

    reg("diylc_end_session",
        "Close the open session (and its window, if headed). The server keeps running; open another "
            + "with diylc_start_session. Errors if no session is open, or if there are unsaved changes "
            + "and force is not set.",
        objectSchema(Map.of("force", boolProp("Discard unsaved changes and close anyway (default false)")), List.of()),
        args -> sessions.endSession(optBool(args, "force", false)));
  }

  private void registerReadTools() {
    reg("diylc_new_project", "Discard the current project and start an empty one.", noArgs(), args -> {
      engine().newProject();
      return "New empty project created.";
    });

    reg("diylc_open_project", "Load a .diy project file from disk, replacing the current project.",
        objectSchema(Map.of("path", stringProp("Absolute path to a .diy file")), List.of("path")), args -> {
          engine().openProject(reqString(args, "path"));
          return "Opened " + engine().currentFileName();
        });

    reg("diylc_describe_project",
        "Return a structured summary of the current project: metadata and every component with its "
            + "type, name, value, and control points (with node names).",
        noArgs(), args -> engine().describeProject());

    reg("diylc_list_component_types",
        "List component types available to instantiate, grouped by category. Optionally filter by "
            + "category substring.",
        objectSchema(Map.of("category", stringProp("Optional category name substring filter")), List.of()),
        args -> engine().listComponentTypes(optString(args, "category", null)));

    reg("diylc_get_netlist",
        "Compute the netlist(s) for the current project. Returns one textual netlist per switch-"
            + "position combination when includeSwitches is true.",
        objectSchema(Map.of("includeSwitches", boolProp("Expand switch positions (default false)")), List.of()),
        args -> engine().getNetlist(optBool(args, "includeSwitches", false)));

    reg("diylc_render_png",
        "Render the current project to a PNG image so the agent can visually inspect the layout.",
        objectSchema(Map.of("includeGrid", boolProp("Draw the grid (default false)")), List.of()),
        args -> ToolResult.of(McpContent.image(engine().renderPngBase64(optBool(args, "includeGrid", false)), "image/png")));

    reg("diylc_get_selection", "Return the currently selected components (same shape as describe).",
        noArgs(), args -> engine().selectionSummary());
  }

  private void registerEditTools() {
    reg("diylc_save_project", "Save the current project to a .diy file on disk.",
        objectSchema(Map.of("path", stringProp("Absolute path to write the .diy file")), List.of("path")), args -> {
          engine().saveProject(reqString(args, "path"));
          return "Saved to " + reqString(args, "path");
        });

    reg("diylc_add_component",
        "Place a component of the given type at unscaled canvas coordinates (snaps to grid). Provide "
            + "one point for single-click components (ICs, boards, jacks) or two+ points for "
            + "point-by-point components (resistors, wires, traces) — one per control point.",
        objectSchema(Map.of(
            "type", stringProp("Component type name, e.g. 'Resistor' (see diylc_list_component_types)"),
            "points", Map.of(
                "type", "array",
                "description", "Control points as [[x,y], ...] in pixels",
                "items", Map.of(
                    "type", "array",
                    "items", Map.of("type", "integer"),
                    "minItems", 2,
                    "maxItems", 2))),
            List.of("type", "points")),
        args -> {
          String type = reqString(args, "type");
          engine().addComponent(type, reqPoints(args, "points"));
          return "Placed " + type + ".";
        });

    reg("diylc_delete_selection", "Delete all currently selected components.", noArgs(), args -> {
      engine().deleteSelection();
      return "Deleted selection.";
    });

    reg("diylc_set_property",
        "Set a property (by editor display name, e.g. 'Value', 'Color', 'Name') on every selected "
            + "component.",
        objectSchema(Map.of(
            "name", stringProp("Property display name"),
            "value", stringProp("New value (parsed by the component's property type)")),
            List.of("name", "value")),
        args -> {
          int n = engine().setSelectionProperty(reqString(args, "name"), reqString(args, "value"));
          return "Updated property on " + n + " component(s).";
        });
  }

  private void registerParityTools() {
    reg("diylc_select_all", "Select all components in the project.", noArgs(), args -> {
      engine().selectAll();
      return "Selected all.";
    });

    reg("diylc_select_matching",
        "Select components matching a search expression (same syntax as the DIYLC search box).",
        objectSchema(Map.of("criteria", stringProp("Search expression")), List.of("criteria")), args -> {
          engine().selectMatching(reqString(args, "criteria"));
          return "Selection updated.";
        });

    reg("diylc_group_selection", "Group the selected components into one logical unit.", noArgs(), args -> {
      engine().groupSelection();
      return "Grouped selection.";
    });

    reg("diylc_ungroup_selection", "Ungroup the selected group(s).", noArgs(), args -> {
      engine().ungroupSelection();
      return "Ungrouped selection.";
    });

    reg("diylc_rotate_selection", "Rotate the selection. direction: 1 = clockwise, -1 = counter-clockwise.",
        objectSchema(Map.of("direction", intProp("1 (CW) or -1 (CCW)")), List.of("direction")), args -> {
          engine().rotateSelection(reqInt(args, "direction"));
          return "Rotated selection.";
        });

    reg("diylc_mirror_selection", "Mirror the selection. direction: 1 = horizontal, -1 = vertical.",
        objectSchema(Map.of("direction", intProp("1 (horizontal) or -1 (vertical)")), List.of("direction")), args -> {
          engine().mirrorSelection(reqInt(args, "direction"));
          return "Mirrored selection.";
        });
  }

  // --- registration + schema helpers -------------------------------------------------------------

  private void reg(String name, String description, Map<String, Object> schema, Tool.Handler handler) {
    tools.put(name, new Tool(name, description, schema, handler));
  }

  private static Map<String, Object> noArgs() {
    return objectSchema(Map.of(), List.of());
  }

  private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    return schema;
  }

  private static Map<String, Object> stringProp(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> intProp(String description) {
    return Map.of("type", "integer", "description", description);
  }

  private static Map<String, Object> boolProp(String description) {
    return Map.of("type", "boolean", "description", description);
  }

  // --- argument extraction -----------------------------------------------------------------------

  private static String reqString(JsonNode args, String key) {
    JsonNode n = args == null ? null : args.get(key);
    if (n == null || n.isNull()) {
      throw new IllegalArgumentException("Missing required argument: " + key);
    }
    return n.asText();
  }

  private static String optString(JsonNode args, String key, String fallback) {
    JsonNode n = args == null ? null : args.get(key);
    return (n == null || n.isNull()) ? fallback : n.asText();
  }

  private static int reqInt(JsonNode args, String key) {
    JsonNode n = args == null ? null : args.get(key);
    if (n == null || n.isNull()) {
      throw new IllegalArgumentException("Missing required argument: " + key);
    }
    return n.asInt();
  }

  private static boolean optBool(JsonNode args, String key, boolean fallback) {
    JsonNode n = args == null ? null : args.get(key);
    return (n == null || n.isNull()) ? fallback : n.asBoolean();
  }

  private static int[][] reqPoints(JsonNode args, String key) {
    JsonNode arr = args == null ? null : args.get(key);
    if (arr == null || !arr.isArray() || arr.isEmpty()) {
      throw new IllegalArgumentException("Missing required point array: " + key);
    }
    int[][] points = new int[arr.size()][2];
    for (int i = 0; i < arr.size(); i++) {
      JsonNode p = arr.get(i);
      if (!p.isArray() || p.size() < 2) {
        throw new IllegalArgumentException("Each point must be [x, y]; bad entry at index " + i);
      }
      points[i][0] = p.get(0).asInt();
      points[i][1] = p.get(1).asInt();
    }
    return points;
  }
}
