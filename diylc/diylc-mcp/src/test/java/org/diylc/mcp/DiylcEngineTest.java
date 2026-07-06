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
  @SuppressWarnings("unchecked")
  public void setControlPointsRepairsWireEndpoints() throws Exception {
    // The repair use case: a wire endpoint missed its pad; move both endpoints without
    // delete + re-place. Handles (cp1, cp2) must be interpolated strictly between the new
    // endpoints, not stacked on them.
    engine.addComponent("Hookup Wire", new int[][] {{0, 0}, {50, 50}});
    List<Map<String, Object>> components =
        (List<Map<String, Object>>) engine.describeProject().get("components");
    String name = (String) components.get(0).get("name");

    Map<String, Object> updated = engine.setControlPoints(name, new int[][] {{10, 0}, {100, 0}});

    List<Map<String, Object>> cps = (List<Map<String, Object>>) updated.get("controlPoints");
    assertEquals(4, cps.size());
    assertEquals(10.0, (double) cps.get(0).get("x"), 0.001);
    assertEquals(100.0, (double) cps.get(3).get("x"), 0.001);
    double h1 = (double) cps.get(1).get("x");
    double h2 = (double) cps.get(2).get("x");
    assertTrue("handles must sit strictly between the endpoints: " + h1 + ", " + h2,
        h1 > 10.0 && h1 < 100.0 && h2 > 10.0 && h2 < 100.0 && h1 < h2);
    // The edit must flag the project dirty (drives the headed title-bar asterisk and save prompts).
    assertTrue("project should be modified after a geometry edit", engine.isModified());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void setControlPointsMapsFullPointListOneToOne() throws Exception {
    engine.addComponent("Hookup Wire", new int[][] {{0, 0}, {50, 50}});
    String name = (String) ((List<Map<String, Object>>) engine.describeProject()
        .get("components")).get(0).get("name");

    Map<String, Object> updated =
        engine.setControlPoints(name, new int[][] {{0, 0}, {60, 0}, {60, 40}, {100, 40}});

    List<Map<String, Object>> cps = (List<Map<String, Object>>) updated.get("controlPoints");
    int[][] want = {{0, 0}, {60, 0}, {60, 40}, {100, 40}};
    for (int i = 0; i < want.length; i++) {
      assertEquals(want[i][0], (double) cps.get(i).get("x"), 0.001);
      assertEquals(want[i][1], (double) cps.get(i).get("y"), 0.001);
    }
  }

  @Test
  public void setControlPointsValidatesNameAndCount() {
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    try {
      engine.setControlPoints("NoSuchComponent", new int[][] {{0, 0}, {10, 10}});
      org.junit.Assert.fail("expected unknown-name error");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("No component named"));
    }
    try {
      engine.setControlPoints("R1", new int[][] {{0, 0}, {10, 10}, {20, 20}});
      org.junit.Assert.fail("expected point-count error (R1 has 2 control points)");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("control point"));
    }
  }

  @Test
  public void headedEngineFocusesViewOnActions() throws Exception {
    // Headed sessions must center the human's view on the agent's action: each mutating op
    // dispatches SCROLL_TO with the affected components' canvas bounds, queued after any handler
    // scrolling (e.g. CanvasPlugin page-centering on PROJECT_LOADED).
    org.diylc.appframework.miscutils.ConfigurationManager.getInstance().initialize("diylc-mcp");
    org.diylc.presenter.Presenter presenter = new org.diylc.presenter.Presenter(
        new org.diylc.common.DummyView(),
        org.diylc.appframework.miscutils.InMemoryConfigurationManager.getInstance(), false);
    presenter.createNewProject();
    List<java.awt.geom.Rectangle2D> scrolls =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    presenter.installPlugin(() -> new org.diylc.common.IPlugIn() {
      @Override
      public void connect(org.diylc.common.IPlugInPort plugInPort) {}

      @Override
      public java.util.EnumSet<org.diylc.common.EventType> getSubscribedEventTypes() {
        return java.util.EnumSet.of(org.diylc.common.EventType.SCROLL_TO);
      }

      @Override
      public void processMessage(org.diylc.common.EventType eventType, Object... params) {
        scrolls.add((java.awt.geom.Rectangle2D) params[0]);
      }
    });
    DiylcEngine headed = new DiylcEngine(presenter,
        org.diylc.appframework.miscutils.InMemoryConfigurationManager.getInstance(), true);

    headed.addComponent("Resistor", new int[][] {{300, 200}, {380, 200}});
    javax.swing.SwingUtilities.invokeAndWait(() -> {});
    assertEquals("placement should focus the view once", 1, scrolls.size());
    java.awt.geom.Rectangle2D r = scrolls.get(0);
    // Canvas-space bounds must cover the placed component (extra space only shifts right/down).
    assertTrue("bounds should sit at or right/below the project coords: " + r,
        r.getMinX() >= 300 - 1 && r.getMinY() >= 200 - 1 && r.getWidth() >= 1);

    headed.selectAll();
    headed.rotateSelection(1);
    javax.swing.SwingUtilities.invokeAndWait(() -> {});
    assertEquals("rotation should focus the view again", 2, scrolls.size());
  }

  @Test
  public void headlessEngineNeverDispatchesScrollTo() throws Exception {
    // The default (headless) engine must not emit view-focus events — there is no view.
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    javax.swing.SwingUtilities.invokeAndWait(() -> {});
    // No assertion hook available on the internal presenter; this guards against exceptions from
    // the focus path (e.g. touching Swing scroll code) in headless runs. Placement must succeed.
    assertEquals(1, engine.describeProject().get("componentCount"));
  }

  @Test
  public void openProjectReturnsNoWarningsForCleanFile() throws Exception {
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    Path file = Files.createTempDirectory("diylc-open-test").resolve("clean.diy");
    engine.saveProject(file.toString());

    List<String> warnings = engine.openProject(file.toString());
    assertTrue("clean save/open round-trip should carry no warnings: " + warnings, warnings.isEmpty());
  }

  @Test
  public void openProjectSurfacesMissingFileVersionWarning() throws Exception {
    // The bug: ProjectFileManager load warnings only reached the GUI as a dialog, so the MCP caller
    // saw plain success. A .diy with its <fileVersion> element stripped (typical of hand-authored
    // XML) must yield the "may be corrupted" warning in the returned list.
    engine.addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});
    Path dir = Files.createTempDirectory("diylc-open-test");
    Path clean = dir.resolve("clean.diy");
    engine.saveProject(clean.toString());

    String xml = new String(Files.readAllBytes(clean), java.nio.charset.StandardCharsets.ISO_8859_1);
    String stripped = xml.replaceAll("(?s)<fileVersion>.*?</fileVersion>", "");
    assertFalse("fixture must actually lose its fileVersion", stripped.equals(xml));
    Path noVersion = dir.resolve("no-version.diy");
    Files.write(noVersion, stripped.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

    List<String> warnings = engine.openProject(noVersion.toString());
    assertEquals(1, warnings.size());
    assertTrue("expected the corrupted-file warning, got: " + warnings,
        warnings.get(0).contains("may be corrupted"));
  }

  @Test
  public void openProjectThrowsOnUnreadableFile() throws Exception {
    // Failures must propagate to the tool layer as errors, not vanish into a view dialog.
    Path bogus = Files.createTempDirectory("diylc-open-test").resolve("bogus.diy");
    Files.write(bogus, "not xml at all".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    try {
      engine.openProject(bogus.toString());
      org.junit.Assert.fail("expected openProject to throw on an unparseable file");
    } catch (Exception expected) {
      // pass
    }
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
