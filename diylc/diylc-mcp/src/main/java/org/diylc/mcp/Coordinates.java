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

/**
 * Pure project↔canvas coordinate math, kept separate from {@link DiylcEngine} so it is unit-testable
 * without a Presenter or a display.
 *
 * <p>It is the inverse of DIYLC's {@code Presenter.scalePoint}, which maps a canvas (mouse) pixel to
 * a project point as {@code project = canvas / zoom - extraSpacePx}. Therefore
 * {@code canvas = (project + extraSpacePx) * zoom}.
 */
public final class Coordinates {

  private Coordinates() {}

  /** Round {@code (project + extraSpacePx) * zoom} to the nearest integer canvas pixel. */
  public static int toCanvas(double project, double extraSpacePx, double zoom) {
    return (int) Math.round((project + extraSpacePx) * zoom);
  }
}
