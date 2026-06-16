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

import org.diylc.swing.gui.MainFrame;

/**
 * One open editing context: the {@link DiylcEngine} the tools drive, the {@link EdtExecutor} their
 * calls run under, and — for a headed session — the live {@link MainFrame} ({@code null} when
 * headless). Created and torn down by {@link SessionManager}; see ADR-0001.
 */
public record Session(DiylcEngine engine, EdtExecutor executor, MainFrame frame) {

  public boolean isHeaded() {
    return frame != null;
  }
}
