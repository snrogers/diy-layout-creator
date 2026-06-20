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

import java.util.List;
import java.util.Map;

/**
 * A pre-built list of MCP content blocks. A {@link Tool.Handler} returns this when it needs control
 * over the content (e.g. emitting an image block). Handlers that just return data can return a plain
 * object instead and the server JSON-encodes it as a single text block.
 */
public record ToolResult(List<Map<String, Object>> content) {

  public static ToolResult of(Map<String, Object> block) {
    return new ToolResult(List.of(block));
  }

  public static ToolResult of(Map<String, Object> first, Map<String, Object> second) {
    return new ToolResult(List.of(first, second));
  }
}
