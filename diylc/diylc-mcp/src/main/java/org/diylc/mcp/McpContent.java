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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single MCP content block (the {@code content[]} entries of a {@code tools/call} result).
 * Factory methods build the two kinds this server emits.
 */
public final class McpContent {

  private McpContent() {}

  public static Map<String, Object> text(String value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "text");
    m.put("text", value);
    return m;
  }

  public static Map<String, Object> image(String base64Data, String mimeType) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "image");
    m.put("data", base64Data);
    m.put("mimeType", mimeType);
    return m;
  }
}
