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
import java.util.Map;

/**
 * One MCP tool: its advertised name/description/input schema and the handler that runs it.
 *
 * <p>Adding a tool is the extension point for reaching full GUI parity — every public
 * {@link org.diylc.presenter.Presenter} verb can be surfaced as one {@code Tool} registered in
 * {@link ToolRegistry}.
 */
public record Tool(String name, String description, Map<String, Object> inputSchema, Handler handler) {

  /**
   * Runs the tool. {@code arguments} is the raw JSON-RPC {@code arguments} object (may be null /
   * missing fields). The return value is serialized into the MCP tool result: a {@link CharSequence}
   * (or anything else) becomes a single text content block; an {@link McpContent} list is passed
   * through verbatim (used for image results).
   */
  @FunctionalInterface
  public interface Handler {
    Object run(JsonNode arguments) throws Exception;
  }
}
