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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal Model Context Protocol server over the stdio transport: newline-delimited JSON-RPC 2.0
 * on stdin/stdout. Implements the handshake ({@code initialize}), {@code tools/list}, and
 * {@code tools/call}; everything else is answered with the standard "method not found" error.
 *
 * <p>A dependency-light hand-rolled loop is deliberate — it keeps the headless DIYLC engine
 * in-process with no heavyweight MCP/reactive-stack dependency. Swap in the official MCP Java SDK
 * later if richer transport features (SSE, sampling, resources) are needed.
 *
 * <p>IMPORTANT: stdout is the protocol channel — only JSON-RPC frames may be written there. All
 * diagnostics go to stderr.
 */
public class McpServer {

  private static final String PROTOCOL_VERSION = "2024-11-05";

  private final ObjectMapper mapper = new ObjectMapper();
  private final ToolRegistry registry;
  private final SessionManager sessions;
  private final BufferedReader in;
  private final BufferedWriter out;

  public McpServer(ToolRegistry registry, SessionManager sessions, PrintStream protocolOut) {
    this.registry = registry;
    this.sessions = sessions;
    this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    this.out = new BufferedWriter(new OutputStreamWriter(protocolOut, StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws Exception {
    // stdout is the JSON-RPC channel and MUST stay clean. DIYLC's transitive logging stack and any
    // stray library prints would otherwise corrupt it, so capture the real stdout for protocol
    // frames and redirect System.out to stderr for the rest of the process.
    PrintStream protocolOut = System.out;
    System.setOut(System.err);

    // The server boots session-less (ADR-0001): no engine/project/window until diylc_start_session.
    SessionManager sessions = new SessionManager();
    // Route the live window's close/File→Exit through the session manager instead of System.exit, so
    // a human closing the window ends the session but leaves the server running.
    org.diylc.swing.gui.MainFrame.exitHandler = sessions::onGuiExit;

    ToolRegistry registry = new ToolRegistry(sessions);
    System.err.println("[diylc-mcp] ready, session-less, " + registry.all().size()
        + " tools. Call diylc_start_session to begin.");

    new McpServer(registry, sessions, protocolOut).run();

    // The process is tied to the MCP client: when it disconnects (stdin EOF) the run loop returns;
    // exit the JVM so any open headed window closes with it.
    System.exit(0);
  }

  public void run() throws Exception {
    String line;
    while ((line = in.readLine()) != null) {
      line = line.trim();
      if (line.isEmpty()) {
        continue;
      }
      JsonNode request;
      try {
        request = mapper.readTree(line);
      } catch (Exception e) {
        writeError(null, -32700, "Parse error: " + e.getMessage());
        continue;
      }
      handle(request);
    }
  }

  private void handle(JsonNode request) throws Exception {
    String method = request.path("method").asText("");
    JsonNode idNode = request.get("id");
    boolean isNotification = idNode == null;

    switch (method) {
      case "initialize" -> respond(idNode, initializeResult());
      case "tools/list" -> respond(idNode, toolsListResult());
      case "tools/call" -> respond(idNode, toolsCallResult(request.path("params")));
      case "ping" -> respond(idNode, mapper.createObjectNode());
      case "notifications/initialized", "notifications/cancelled" -> {
        /* notifications: no response */
      }
      default -> {
        if (!isNotification) {
          writeError(idNode, -32601, "Method not found: " + method);
        }
      }
    }
  }

  private ObjectNode initializeResult() {
    ObjectNode result = mapper.createObjectNode();
    result.put("protocolVersion", PROTOCOL_VERSION);
    ObjectNode caps = result.putObject("capabilities");
    caps.putObject("tools");
    ObjectNode info = result.putObject("serverInfo");
    info.put("name", "diylc-mcp");
    info.put("version", "5.15.0");
    return result;
  }

  private ObjectNode toolsListResult() {
    ObjectNode result = mapper.createObjectNode();
    var toolsArray = result.putArray("tools");
    for (Tool tool : registry.all()) {
      ObjectNode t = toolsArray.addObject();
      t.put("name", tool.name());
      t.put("description", tool.description());
      t.set("inputSchema", mapper.valueToTree(tool.inputSchema()));
    }
    return result;
  }

  private ObjectNode toolsCallResult(JsonNode params) {
    String name = params.path("name").asText("");
    Tool tool = registry.get(name);
    if (tool == null) {
      return errorContent("Unknown tool: " + name);
    }
    JsonNode arguments = params.get("arguments");
    try {
      // Run the whole tool call on the open session's executor: the EDT for a headed session (atomic
      // against the human's UI events, never touching the shared Presenter off-thread), or inline when
      // headless / session-less.
      Object raw = sessions.executorForCall().call(() -> tool.handler().run(arguments));
      return successContent(raw);
    } catch (Exception e) {
      // MCP convention: tool execution failures are reported as isError results, not protocol errors,
      // so the model can read and react to them.
      return errorContent(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Wrap a handler's return value into a {@code tools/call} result. */
  private ObjectNode successContent(Object raw) {
    List<Map<String, Object>> content;
    if (raw instanceof ToolResult tr) {
      content = tr.content();
    } else if (raw instanceof CharSequence cs) {
      content = List.of(McpContent.text(cs.toString()));
    } else {
      // Arbitrary data -> pretty JSON text block.
      String json;
      try {
        json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw);
      } catch (Exception e) {
        json = String.valueOf(raw);
      }
      content = List.of(McpContent.text(json));
    }
    ObjectNode result = mapper.createObjectNode();
    result.set("content", mapper.valueToTree(content));
    result.put("isError", false);
    return result;
  }

  private ObjectNode errorContent(String message) {
    ObjectNode result = mapper.createObjectNode();
    List<Map<String, Object>> content = new ArrayList<>();
    content.add(McpContent.text(message));
    result.set("content", mapper.valueToTree(content));
    result.put("isError", true);
    return result;
  }

  // --- JSON-RPC framing --------------------------------------------------------------------------

  private void respond(JsonNode id, JsonNode result) throws Exception {
    if (id == null) {
      return; // notification — never reply
    }
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result);
    writeFrame(response);
  }

  private void writeError(JsonNode id, int code, String message) throws Exception {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id == null ? null : id);
    ObjectNode error = response.putObject("error");
    error.put("code", code);
    error.put("message", message);
    writeFrame(response);
  }

  private synchronized void writeFrame(ObjectNode response) throws Exception {
    out.write(mapper.writeValueAsString(response));
    out.write("\n");
    out.flush();
  }
}
