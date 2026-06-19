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

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.diylc.appframework.miscutils.ConfigurationManager;
import org.diylc.appframework.miscutils.IConfigurationManager;
import org.diylc.appframework.miscutils.InMemoryConfigurationManager;
import org.diylc.common.ComponentType;
import org.diylc.common.DrawOption;
import org.diylc.common.DummyView;
import org.diylc.common.PropertyWrapper;
import org.diylc.core.CreationMethod;
import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.netlist.Netlist;
import org.diylc.presenter.Presenter;

/**
 * Thin, single-session wrapper around a {@link Presenter}.
 *
 * <p>This is the engine the MCP tools call. It reuses the same controller the Swing UI uses, so
 * behaviour — grid snapping, control-point/node resolution, serialization, rendering — is identical
 * to the desktop app.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Headless</b> (default ctor): builds a {@link Presenter} over a no-op {@link DummyView}; no
 *       GUI. Used for pure agent/CI runs.
 *   <li><b>Headed</b> ({@link #forPresenter}): drives the {@code Presenter} of a live {@code MainFrame}
 *       so the agent and a human co-edit one shared project. The caller marshals every method onto the
 *       Swing EDT (see {@link EdtExecutor}); this class does no locking of its own.
 * </ul>
 *
 * <p>Agent tools speak <b>project coordinates</b>. Placement converts them to canvas pixels with
 * {@link #toCanvas} using the Presenter's live extra-space and zoom, so it lands correctly regardless
 * of how the human has zoomed or panned, and without mutating any view config.
 */
public class DiylcEngine {

  private final Presenter presenter;
  // The same config manager the Presenter reads — so our extra-space check mirrors scalePoint exactly.
  private final IConfigurationManager<?> configManager;

  /** Headless: own Presenter over a DummyView, fresh empty project. */
  public DiylcEngine() {
    // The configuration manager backs every Presenter operation (snap-to-grid, defaults, …) and must
    // be initialized before the Presenter is constructed, or its config map is null. Mirrors TestBase.
    ConfigurationManager.getInstance().initialize("diylc-mcp");
    this.configManager = InMemoryConfigurationManager.getInstance();
    // importVariantsAndBlocks=false: don't touch the user's on-disk DIYLC config in a server.
    this.presenter = new Presenter(new DummyView(), configManager, false);
    this.presenter.createNewProject();
  }

  /** Headed: drive an existing Presenter (e.g. {@code mainFrame.getPresenter()}). */
  private DiylcEngine(Presenter presenter, IConfigurationManager<?> configManager) {
    this.presenter = presenter;
    this.configManager = configManager;
  }

  /**
   * Wrap a live frame's Presenter for co-editing. The frame owns config/project lifecycle. MainFrame
   * builds its Presenter over {@link ConfigurationManager#getInstance()}, so pass that here.
   */
  public static DiylcEngine forPresenter(Presenter presenter) {
    return new DiylcEngine(presenter, ConfigurationManager.getInstance());
  }

  // --- Project lifecycle -------------------------------------------------------------------------

  public void newProject() {
    presenter.createNewProject();
  }

  public void openProject(String path) {
    presenter.loadProjectFromFile(path);
  }

  public void saveProject(String path) {
    presenter.saveProjectToFile(path, false);
  }

  public String currentFileName() {
    return presenter.getCurrentFileName();
  }

  public boolean isModified() {
    return presenter.isProjectModified();
  }

  // --- Read / inspection -------------------------------------------------------------------------

  /**
   * A JSON-serializable summary of the current project: metadata plus one entry per component with
   * its type, name, value and control points (each control point carries its node name, which is
   * what nets are keyed on). This is deliberately hand-built so the output is guaranteed to
   * serialize cleanly and stay stable regardless of internal model changes.
   */
  public Map<String, Object> describeProject() {
    Project project = presenter.getCurrentProject();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("title", project.getTitle());
    out.put("author", project.getAuthor());
    out.put("width", String.valueOf(project.getWidth()));
    out.put("height", String.valueOf(project.getHeight()));
    out.put("gridSpacing", String.valueOf(project.getGridSpacing()));

    List<Map<String, Object>> components = new ArrayList<>();
    for (IDIYComponent<?> c : project.getComponents()) {
      components.add(describeComponent(c));
    }
    out.put("componentCount", components.size());
    out.put("components", components);
    return out;
  }

  private Map<String, Object> describeComponent(IDIYComponent<?> c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", c.getName());
    m.put("type", c.getClass().getSimpleName());
    m.put("value", c.getValueForDisplay());
    List<Map<String, Object>> points = new ArrayList<>();
    for (int i = 0; i < c.getControlPointCount(); i++) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("x", c.getControlPoint(i).getX());
      p.put("y", c.getControlPoint(i).getY());
      p.put("node", c.getControlPointNodeName(i));
      points.add(p);
    }
    m.put("controlPoints", points);
    return m;
  }

  /** Component types available for instantiation, grouped category -> [type names]. */
  public Map<String, Object> listComponentTypes(String categoryFilter) {
    Map<String, List<ComponentType>> byCategory = presenter.getComponentTypes();
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<ComponentType>> e : byCategory.entrySet()) {
      if (categoryFilter != null && !e.getKey().toLowerCase().contains(categoryFilter.toLowerCase())) {
        continue;
      }
      List<Map<String, Object>> types = new ArrayList<>();
      for (ComponentType t : e.getValue()) {
        Map<String, Object> tm = new LinkedHashMap<>();
        tm.put("name", t.getName());
        tm.put("description", t.getDescription());
        tm.put("class", t.getInstanceClass().getName());
        types.add(tm);
      }
      out.put(e.getKey(), types);
    }
    return out;
  }

  /** One textual netlist per switch-position combination. */
  public List<String> getNetlist(boolean includeSwitches) throws Exception {
    List<Netlist> netlists = presenter.extractNetlists(includeSwitches);
    List<String> out = new ArrayList<>();
    if (netlists != null) {
      for (Netlist n : netlists) {
        out.add(n.toString());
      }
    }
    return out;
  }

  /** Render the current project to a PNG and return it base64-encoded. */
  public String renderPngBase64(boolean includeGrid) throws Exception {
    return renderPngBase64(new RenderOpts(false, null, null, null, RenderOpts.DEFAULT_MARGIN, includeGrid));
  }

  /**
   * Render options for {@link #renderPngBase64(RenderOpts)}. All framing knobs are backward
   * compatible; the no-arg path ({@code fitContent=false}, no zoom/width/height) reproduces the
   * original full-canvas render exactly.
   */
  public static final class RenderOpts {
    static final int DEFAULT_MARGIN = 10;
    /** Frame the content bbox instead of the whole canvas page. */
    public final boolean fitContent;
    /** Render-only zoom (px per project-space px); null = derive from width/height or default. */
    public final Double zoom;
    /** Target output width (px); null = unconstrained. */
    public final Integer width;
    /** Target output height (px); null = unconstrained. */
    public final Integer height;
    /** Padding (px) around a content crop. */
    public final int margin;
    public final boolean includeGrid;

    public RenderOpts(boolean fitContent, Double zoom, Integer width, Integer height, int margin, boolean includeGrid) {
      this.fitContent = fitContent;
      this.zoom = zoom;
      this.width = width;
      this.height = height;
      this.margin = margin;
      this.includeGrid = includeGrid;
    }

    /** True when none of the new framing knobs are set (original full-canvas render). */
    boolean isDefault() {
      return !fitContent && zoom == null && width == null && height == null;
    }
  }

  /**
   * Render the current project to a PNG. By default (no framing knobs) this reproduces the original
   * full-canvas render bit-for-bit. With {@code fitContent} it crops to the bounding box of all
   * components (control points, project space) plus {@code margin}; {@code zoom} / {@code width} /
   * {@code height} size the output. Render parameters never mutate the session's live view state.
   */
  public String renderPngBase64(RenderOpts o) throws Exception {
    EnumSet<DrawOption> options = EnumSet.of(DrawOption.ANTIALIASING, DrawOption.CONTROL_POINTS);
    if (o.includeGrid) {
      options.add(DrawOption.GRID);
    }

    // Default path: full canvas at the presenter's own zoom — original behaviour, unchanged.
    if (o.isDefault()) {
      Dimension dim = presenter.getCanvasDimensions(true, false);
      return renderAtCanvasSize(dim.width, dim.height, options, 0, 0, 0);
    }

    // Framed path. Resolve the project-space region to frame and an output zoom (px/project-px).
    Rectangle2D region = o.fitContent ? contentBoundsProject() : canvasBoundsProject();
    double oz = outputZoom(region, o);

    int imgW = Math.max(1, (int) Math.round(region.getWidth() * oz + 2 * o.margin));
    int imgH = Math.max(1, (int) Math.round(region.getHeight() * oz + 2 * o.margin));
    if (o.width != null) {
      imgW = o.width;
    }
    if (o.height != null) {
      imgH = o.height;
    }
    // Translate so the region's origin maps to (margin, margin): a component at project coord p
    // renders at margin + (p - region.x) * oz. draw() applies effZoom = oz internally via
    // externalZoom = oz * PIXEL_SIZE (its base zoom is 1/PIXEL_SIZE), so pre-translate g2d by
    // (margin - region.x * oz, margin - region.y * oz).
    double tx = o.margin - region.getX() * oz;
    double ty = o.margin - region.getY() * oz;
    return renderAtCanvasSize(imgW, imgH, options, oz, tx, ty);
  }

  /** Render an imgW×imgH image; if {@code effZoom > 0}, translate g2d by (tx,ty) and pass externalZoom. */
  private String renderAtCanvasSize(int imgW, int imgH, EnumSet<DrawOption> options, double effZoom,
      double tx, double ty) throws Exception {
    BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = image.createGraphics();
    try {
      if (effZoom > 0) {
        g2d.translate(tx, ty);
      }
      Double externalZoom = effZoom > 0 ? effZoom * org.diylc.utils.Constants.PIXEL_SIZE : null;
      presenter.draw(g2d, options, null, externalZoom, null, null);
    } finally {
      g2d.dispose();
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(image, "png", baos);
    return Base64.getEncoder().encodeToString(baos.toByteArray());
  }

  /** Output zoom (image px per project-space px) given the region and the requested knobs. */
  private static double outputZoom(Rectangle2D region, RenderOpts o) {
    if (o.zoom != null) {
      return o.zoom;
    }
    if (o.width != null || o.height != null) {
      double zx = o.width != null ? (o.width - 2 * o.margin) / Math.max(1, region.getWidth()) : Double.POSITIVE_INFINITY;
      double zy = o.height != null ? (o.height - 2 * o.margin) / Math.max(1, region.getHeight()) : Double.POSITIVE_INFINITY;
      double z = Math.min(zx, zy);
      return z > 0 && Double.isFinite(z) ? z : 1.0;
    }
    return 1.0;
  }

  /** Bounding box (project space) of every component's control points; null if the project is empty. */
  private java.awt.geom.Rectangle2D contentBoundsProject() {
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
    for (IDIYComponent<?> c : presenter.getCurrentProject().getComponents()) {
      for (int i = 0; i < c.getControlPointCount(); i++) {
        Point2D p = c.getControlPoint(i);
        minX = Math.min(minX, p.getX());
        minY = Math.min(minY, p.getY());
        maxX = Math.max(maxX, p.getX());
        maxY = Math.max(maxY, p.getY());
      }
    }
    if (!Double.isFinite(minX)) {
      // Empty project: fall back to the canvas page.
      return canvasBoundsProject();
    }
    return new java.awt.geom.Rectangle2D.Double(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
  }

  /** The project's page size in project-space pixels. */
  private java.awt.geom.Rectangle2D canvasBoundsProject() {
    org.diylc.core.Project p = presenter.getCurrentProject();
    double w = p.getWidth().convertToPixels();
    double h = p.getHeight().convertToPixels();
    return new java.awt.geom.Rectangle2D.Double(0, 0, w, h);
  }

  // --- Selection helpers -------------------------------------------------------------------------

  public List<Map<String, Object>> selectionSummary() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (IDIYComponent<?> c : presenter.getSelectedComponents()) {
      out.add(describeComponent(c));
    }
    return out;
  }

  public void selectAll() {
    presenter.selectAll(0);
  }

  public void selectMatching(String criteria) {
    presenter.selectMatching(criteria);
  }

  // --- Editing -----------------------------------------------------------------------------------

  /**
   * Place a component of the named type, reusing the same type-slot + click flow the GUI uses (so
   * grid snapping and node resolution apply identically).
   *
   * <p>The number of points required depends on the type's creation method: SINGLE_CLICK components
   * (ICs, boards, jacks, …) take exactly one point; POINT_BY_POINT components (resistors, wires,
   * traces, …) take two or more — one click per control point. The slot is set once and every point
   * is clicked against it, so the component is committed in a single call.
   *
   * @param points unscaled canvas coordinates {@code [[x,y], ...]}
   */
  public void addComponent(String typeName, int[][] points) {
    ComponentType type = findType(typeName);
    if (type == null) {
      throw new IllegalArgumentException("Unknown component type: " + typeName);
    }
    boolean singleClick = type.getCreationMethod() == CreationMethod.SINGLE_CLICK;
    int needed = singleClick ? 1 : 2;
    if (points == null || points.length < needed) {
      throw new IllegalArgumentException(
          type.getName() + " (" + type.getCreationMethod() + ") requires at least " + needed + " point(s).");
    }
    presenter.setNewComponentTypeSlot(type, null, null, false);
    int clicks = singleClick ? 1 : points.length;
    for (int i = 0; i < clicks; i++) {
      Point p = toCanvas(points[i][0], points[i][1]);
      // DIYLC places a control point at the current cursor position on click. The cursor is set by
      // mouseMoved, so emit a move before EVERY click — including the first. Without the leading move
      // a SINGLE_CLICK component (one click) lands at the stale last cursor (origin on a fresh
      // project); for POINT_BY_POINT the leading move is harmless, since click 0 commits point 0 at
      // the click location anyway.
      presenter.mouseMoved(p, false, false, false);
      presenter.mouseClicked(p, 1 /* left */, false, false, false, 1);
    }
  }

  /**
   * Convert a project-space point to a canvas (mouse) pixel, inverting {@code Presenter.scalePoint}:
   * {@code canvas = (project + extraSpacePx) * zoom}. The pixel margin must come from
   * {@code DrawingManager.getExtraSpace(project)} — the value scalePoint actually uses — not the
   * public {@code Presenter.getExtraSpace()}, which returns a unit-converted display value. Read live
   * each call, so placement is correct whatever the human's view state, and nothing is mutated.
   */
  private Point toCanvas(int projectX, int projectY) {
    double extraSpace =
        isExtraSpaceEnabled() ? presenter.getDrawingManager().getExtraSpace(presenter.getCurrentProject()) : 0d;
    double zoom = presenter.getZoomLevel();
    return new Point(Coordinates.toCanvas(projectX, extraSpace, zoom), Coordinates.toCanvas(projectY, extraSpace, zoom));
  }

  private boolean isExtraSpaceEnabled() {
    return configManager.readBoolean(org.diylc.common.IPlugInPort.EXTRA_SPACE_KEY, true);
  }

  public void deleteSelection() {
    presenter.deleteSelectedComponents();
  }

  public void groupSelection() {
    presenter.groupSelectedComponents();
  }

  public void ungroupSelection() {
    presenter.ungroupSelectedComponents();
  }

  public void rotateSelection(int direction) {
    presenter.rotateSelection(direction);
  }

  public void mirrorSelection(int direction) {
    presenter.mirrorSelection(direction);
  }

  /**
   * Set a property by display name on every currently selected component. Property names match
   * those shown in the DIYLC editor (e.g. "Value", "Color", "Name").
   */
  public int setSelectionProperty(String propertyName, Object value) {
    Collection<IDIYComponent<?>> selection = presenter.getSelectedComponents();
    int changed = 0;
    for (IDIYComponent<?> c : selection) {
      List<PropertyWrapper> props = presenter.getProperties(c);
      for (PropertyWrapper p : props) {
        if (p.getName().equalsIgnoreCase(propertyName)) {
          p.setValue(coerce(value, p.getType()));
          presenter.applyProperties(c, props);
          changed++;
          break;
        }
      }
    }
    return changed;
  }

  /**
   * Coerce a JSON-supplied value (usually a String) into the type a {@link PropertyWrapper} expects.
   * Handles the common DIYLC property types: String, primitives/booleans, enums, and measures
   * (Resistance, Capacitance, …) via their static {@code parseXxx(String)} factory — located by
   * return type rather than name, since DIYLC's factory names are inconsistent.
   */
  private static Object coerce(Object value, Class<?> type) {
    if (value == null || type.isInstance(value)) {
      return value;
    }
    String s = value.toString();
    if (type == String.class) {
      return s;
    }
    if (type == Boolean.class || type == boolean.class) {
      return Boolean.valueOf(s);
    }
    if (type == Integer.class || type == int.class) {
      return Integer.valueOf(s);
    }
    if (type == Double.class || type == double.class) {
      return Double.valueOf(s);
    }
    if (type.isEnum()) {
      for (Object constant : type.getEnumConstants()) {
        if (((Enum<?>) constant).name().equalsIgnoreCase(s) || constant.toString().equalsIgnoreCase(s)) {
          return constant;
        }
      }
      throw new IllegalArgumentException("No enum constant " + type.getSimpleName() + " matching '" + s + "'");
    }
    // Measures and similar: a public static factory returning the target type from a single String.
    for (Method m : type.getMethods()) {
      if (Modifier.isStatic(m.getModifiers())
          && type.isAssignableFrom(m.getReturnType())
          && m.getParameterCount() == 1
          && m.getParameterTypes()[0] == String.class) {
        try {
          return m.invoke(null, s);
        } catch (Exception ignored) {
          // try the next candidate factory
        }
      }
    }
    throw new IllegalArgumentException(
        "Don't know how to parse '" + s + "' into property type " + type.getName());
  }

  // --- internals ---------------------------------------------------------------------------------

  private ComponentType findType(String typeName) {
    for (List<ComponentType> types : presenter.getComponentTypes().values()) {
      for (ComponentType t : types) {
        if (t.getName().equalsIgnoreCase(typeName)) {
          return t;
        }
      }
    }
    return null;
  }
}
