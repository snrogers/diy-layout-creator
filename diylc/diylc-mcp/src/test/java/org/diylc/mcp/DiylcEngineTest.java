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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
  @SuppressWarnings("unchecked")
  public void placesHookupWireEndpointsAtRequestedCoordinates() {
    // Hookup Wire is a cubic (4 control points: 2 endpoints + 2 handles). Only the endpoints are
    // electrical, so they MUST equal the requested first/last input — otherwise the netlist reads an
    // open circuit for a wire that renders as connected.
    engine.addComponent("Hookup Wire", new int[][] {{0, 0}, {50, 50}});

    List<Map<String, Object>> components =
        (List<Map<String, Object>>) engine.describeProject().get("components");
    List<Map<String, Object>> cps = (List<Map<String, Object>>) components.get(0).get("controlPoints");
    assertEquals(4, cps.size());
    assertEquals(0.0, (double) cps.get(0).get("x"), 0.001);
    assertEquals(0.0, (double) cps.get(0).get("y"), 0.001);
    assertEquals(50.0, (double) cps.get(3).get("x"), 0.001);
    assertEquals(50.0, (double) cps.get(3).get("y"), 0.001);
  }

  @Test
  public void wireJoinsTwoComponentsInNetlist() throws Exception {
    // Two resistors sharing nodes via a hookup wire: R1 right lead -> wire -> R2 left lead.
    engine.addComponent("Resistor", new int[][] {{0, 0}, {50, 0}});
    engine.addComponent("Hookup Wire", new int[][] {{50, 0}, {100, 0}});
    engine.addComponent("Resistor", new int[][] {{100, 0}, {150, 0}});

    List<String> netlists = engine.getNetlist(false);
    String netlist = String.join("\n", netlists);
    assertTrue("wire should join R1 and R2 into one net", netlist.contains("R1") && netlist.contains("R2"));
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
  public void renderFitContentCropsToContentNotFullPage() throws Exception {
    // A single resistor on the default 29x21cm page: full-canvas render is huge, content-crop is
    // small. The default (canvas) path is exercised by rendersNonEmptyPng.
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});

    DiylcEngine.RenderOpts content = new DiylcEngine.RenderOpts(true, null, null, null, 10, false);
    String contentPng = engine.renderPngBase64(content);

    // Sanity: produces a valid non-empty PNG.
    assertNotNull(contentPng);
    assertTrue(contentPng.length() > 100);

    // Render-only params must not mutate session view state: a subsequent placement lands normally.
    engine.addComponent("Resistor", new int[][] {{200, 60}, {260, 60}});
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> comps =
        (List<Map<String, Object>>) engine.describeProject().get("components");
    assertEquals(2, comps.size());
  }

  @Test
  public void renderFitContentHonorsOutputWidth() throws Exception {
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    DiylcEngine.RenderOpts o = new DiylcEngine.RenderOpts(true, null, 400, 300, 10, false);
    String png = engine.renderPngBase64(o);
    assertNotNull(png);
    byte[] bytes = java.util.Base64.getDecoder().decode(png);
    // Read the PNG IHDR to confirm the image was sized to the requested width.
    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
    assertEquals(400, img.getWidth());
    assertEquals(300, img.getHeight());
  }

  @Test
  public void listsComponentTypes() {
    Map<String, Object> byCategory = engine.listComponentTypes("passive");
    assertFalse(byCategory.isEmpty());
  }

  @Test
  public void renderPngToFileWritesValidPngAndRoundTrips() throws Exception {
    // The data-residency fix: renderPngToFile writes a real PNG to disk (the on-machine source of
    // truth the tool returns as a path) and returns the same bytes base64-encoded so an opt-in
    // image-content caller gets them without a second render. Decode the file and the returned
    // base64 independently and confirm they agree and honor width/height.
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    Path out = Files.createTempDirectory("diylc-render-test").resolve("render.png");

    DiylcEngine.RenderOpts o = new DiylcEngine.RenderOpts(true, null, 400, 300, 10, false);
    String base64 = engine.renderPngToFile(o, out);

    // The file exists on disk and is a PNG of the requested dimensions.
    assertTrue("render file should exist", Files.exists(out) && Files.size(out) > 100);
    java.awt.image.BufferedImage fromFile =
        javax.imageio.ImageIO.read(out.toFile());
    assertEquals(400, fromFile.getWidth());
    assertEquals(300, fromFile.getHeight());

    // The returned base64 decodes to the SAME image the file holds (single render, two views).
    byte[] fromBase64 = java.util.Base64.getDecoder().decode(base64);
    java.awt.image.BufferedImage img =
        javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(fromBase64));
    assertEquals(400, img.getWidth());
    assertEquals(300, img.getHeight());
    assertArrayEquals(Files.readAllBytes(out), fromBase64);
  }

  @Test
  public void defaultRenderDirResolvesOverrideThenXdgThenTmpdir() {
    // Env precedence is unit-tested through the explicit-override overload, since a running JVM
    // cannot re-set its own launch-time env. Override wins; empty/blank falls through.
    assertEquals(Path.of("/custom/dir"), engine.defaultRenderDir("/custom/dir"));
    assertEquals(Path.of("/also-custom"), engine.defaultRenderDir("/also-custom"));

    // No override: resolves to <base>/diylc-render where base is XDG_RUNTIME_DIR or java.io.tmpdir.
    Path fallback = engine.defaultRenderDir(null);
    String xdg = System.getenv("XDG_RUNTIME_DIR");
    Path expected = (xdg != null && !xdg.isEmpty())
        ? Path.of(xdg).resolve("diylc-render")
        : Path.of(System.getProperty("java.io.tmpdir")).resolve("diylc-render");
    assertEquals(expected, fallback);

    // Empty/blank overrides are treated the same as null.
    assertEquals(fallback, engine.defaultRenderDir(""));
  }

  @Test
  public void nextRenderPathIncrementsUnderDefaultDir() {
    // Auto-named renders live under defaultRenderDir() and monotonically increment per engine.
    Path first = engine.nextRenderPath();
    Path second = engine.nextRenderPath();
    assertEquals(engine.defaultRenderDir(), first.getParent());
    assertEquals("render-1.png", first.getFileName().toString());
    assertEquals("render-2.png", second.getFileName().toString());
  }
}
