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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/** Exercises the headless engine end to end: placement, typed properties, netlist, and rendering. */
public class DiylcEngineTest {

  private DiylcEngine engine;

  @Before
  public void setUp() {
    System.setProperty("java.awt.headless", "true");
    engine = new DiylcEngine();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void placesPointByPointComponentAtExactCoordinates() {
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});

    Map<String, Object> project = engine.describeProject();
    assertEquals(1, project.get("componentCount"));

    List<Map<String, Object>> components = (List<Map<String, Object>>) project.get("components");
    Map<String, Object> resistor = components.get(0);
    assertEquals("Resistor", resistor.get("type"));

    // The two control points must land on exactly the requested pixels (1:1 coordinate mapping).
    List<Map<String, Object>> points = (List<Map<String, Object>>) resistor.get("controlPoints");
    assertEquals(2, points.size());
    assertEquals(60.0, (double) points.get(0).get("x"), 0.001);
    assertEquals(60.0, (double) points.get(0).get("y"), 0.001);
    assertEquals(140.0, (double) points.get(1).get("x"), 0.001);
    assertEquals(60.0, (double) points.get(1).get("y"), 0.001);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void placesSingleClickComponentAtRequestedCoordinate() {
    // SINGLE_CLICK components (Dot, Pin Header, pads, ICs, …) must place at the requested first
    // coordinate, not collapse onto the canvas origin. DIYLC snaps SINGLE_CLICK placement to the
    // grid (default 0.1in ≈ 9.6px), so assert within one grid spacing — the regression symptom is
    // landing at (0,0), i.e. nowhere near (150,150).
    engine.addComponent("Dot", new int[][] {{150, 150}});

    Map<String, Object> project = engine.describeProject();
    List<Map<String, Object>> components = (List<Map<String, Object>>) project.get("components");
    Map<String, Object> dot = components.get(0);

    Map<String, Object> first = (Map<String, Object>) ((List<?>) dot.get("controlPoints")).get(0);
    double x = (double) first.get("x");
    double y = (double) first.get("y");
    // Lands near the requested point (within one grid spacing) — not at the origin.
    assertEquals(150.0, x, 15.0);
    assertEquals(150.0, y, 15.0);
    assertTrue("Dot must not land at origin", x > 10.0 && y > 10.0);
  }

  @Test
  public void setsTypedMeasureProperty() {
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    engine.selectAll();

    int changed = engine.setSelectionProperty("Value", "4.7k");
    assertEquals(1, changed);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> components =
        (List<Map<String, Object>>) engine.describeProject().get("components");
    // The string "4.7k" must have been parsed into a Resistance and round-trip through display.
    assertTrue(String.valueOf(components.get(0).get("value")).toUpperCase().contains("4.7"));
  }

  @Test
  public void computesNetlistForConnectedComponents() throws Exception {
    // Two resistors sharing the node at (140,60) form one net.
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    engine.addComponent("Resistor", new int[][] {{140, 60}, {220, 60}});

    List<String> netlists = engine.getNetlist(false);
    assertFalse(netlists.isEmpty());
    String netlist = String.join("\n", netlists);
    assertTrue("netlist should reference the placed resistors", netlist.contains("R1") && netlist.contains("R2"));
  }

  @Test
  public void rendersNonEmptyPng() throws Exception {
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    String base64 = engine.renderPngBase64(false);
    assertNotNull(base64);
    assertTrue("expected a non-trivial PNG", base64.length() > 100);
  }

  @Test
  public void listsComponentTypes() {
    Map<String, Object> byCategory = engine.listComponentTypes("passive");
    assertFalse(byCategory.isEmpty());
  }
}
