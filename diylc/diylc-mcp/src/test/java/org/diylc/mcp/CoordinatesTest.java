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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure conversion math — no Presenter, no display. Verifies it inverts scalePoint exactly. */
public class CoordinatesTest {

  /** scalePoint: project = canvas/zoom - extraSpace. toCanvas must round-trip it. */
  private static double scalePoint(int canvas, double extraSpace, double zoom) {
    return canvas / zoom - extraSpace;
  }

  @Test
  public void noMarginNoZoomIsIdentity() {
    assertEquals(60, Coordinates.toCanvas(60, 0d, 1d));
    assertEquals(140, Coordinates.toCanvas(140, 0d, 1d));
  }

  @Test
  public void addsMarginAtUnitZoom() {
    assertEquals(640, Coordinates.toCanvas(60, 580d, 1d));
  }

  @Test
  public void appliesZoom() {
    assertEquals(300, Coordinates.toCanvas(150, 0d, 2d));
    assertEquals(75, Coordinates.toCanvas(150, 0d, 0.5d));
  }

  @Test
  public void roundTripsThroughScalePoint() {
    double[] zooms = {1d, 2d, 0.5d, 1.25d};
    double[] margins = {0d, 580d, 123.4d};
    int[] projects = {0, 60, 140, 1000};
    for (double zoom : zooms) {
      for (double margin : margins) {
        for (int project : projects) {
          int canvas = Coordinates.toCanvas(project, margin, zoom);
          double back = scalePoint(canvas, margin, zoom);
          // Allow sub-pixel rounding error; DIYLC then snaps to grid anyway.
          assertEquals("zoom=" + zoom + " margin=" + margin + " project=" + project,
              project, back, 1.0 / zoom);
        }
      }
    }
  }
}
