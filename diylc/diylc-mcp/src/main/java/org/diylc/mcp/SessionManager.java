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

import java.awt.GraphicsEnvironment;
import java.awt.event.WindowListener;
import org.diylc.swing.gui.MainFrame;

/**
 * Owns the server's single optional {@link Session} and its explicit lifecycle (ADR-0001).
 *
 * <p>The server boots session-less. {@code diylc_start_session} opens a session, {@code diylc_end_session}
 * closes it, and every other tool calls {@link #requireEngine()} — which errors when none is open.
 * Closing a session does not stop the server; another can be opened afterwards.
 */
public class SessionManager {

  private Session current; // null when no session is open

  public synchronized boolean hasSession() {
    return current != null;
  }

  /** The executor tool calls should run under: the open session's, or direct when none is open. */
  public synchronized EdtExecutor executorForCall() {
    return current != null ? current.executor() : EdtExecutor.direct();
  }

  /** The engine for the open session, or an error if none is open. */
  public synchronized DiylcEngine requireEngine() {
    if (current == null) {
      throw new IllegalStateException("No open session. Call diylc_start_session first.");
    }
    return current.engine();
  }

  /**
   * Open a session. Errors if one is already open, or if {@code headed} is requested with no display.
   */
  public synchronized String startSession(boolean headed) {
    if (current != null) {
      throw new IllegalStateException(
          "A session is already open (" + (current.isHeaded() ? "headed" : "headless")
              + "). Call diylc_end_session first.");
    }
    if (headed) {
      if (GraphicsEnvironment.isHeadless()) {
        throw new IllegalStateException(
            "headed=true requires a display, but none is available (GraphicsEnvironment.isHeadless()). "
                + "Relaunch the server without -Djava.awt.headless=true on a machine with a display, or "
                + "use headed=false.");
      }
      MainFrame frame = buildMainFrame();
      current = new Session(DiylcEngine.forPresenter(frame.getPresenter()), EdtExecutor.edt(), frame);
      return "Opened headed session (live DIYLC window).";
    }
    current = new Session(new DiylcEngine(), EdtExecutor.direct(), null);
    return "Opened headless session.";
  }

  /**
   * Close the open session. Errors if none is open, or if the project has unsaved changes and
   * {@code force} is false. For a headed session, disposes the window without re-prompting (the agent
   * already made the keep-or-discard decision via {@code force}).
   */
  public synchronized String endSession(boolean force) {
    if (current == null) {
      throw new IllegalStateException("No open session to end.");
    }
    if (current.engine().isModified() && !force) {
      throw new IllegalStateException(
          "Session has unsaved changes. Save first (diylc_save_project) or pass force=true to discard.");
    }
    Session ending = current;
    current = null; // clear first so any window-close hook becomes a no-op
    if (ending.isHeaded()) {
      disposeFrame(ending.frame());
    }
    return "Session ended.";
  }

  /**
   * Invoked by {@link MainFrame#exitHandler} when the human closes the window / picks File→Exit. The
   * frame has already run its own save-check and disposed itself, so we only drop the session. The
   * server keeps running; the agent learns the session is gone on its next tool call.
   */
  public synchronized void onGuiExit() {
    current = null;
  }

  // EdtExecutor.edt() runs the body inline when already on the EDT (e.g. endSession is itself invoked
  // on the EDT for a headed session) and via invokeAndWait otherwise (e.g. startSession runs off-EDT).
  private static final EdtExecutor EDT = EdtExecutor.edt();

  private static MainFrame buildMainFrame() {
    // MainFrame builds its Presenter over ConfigurationManager.getInstance(), which must be
    // initialized first or its config map is null. Use the "diylc" profile (the real desktop-app
    // config) so the headed window behaves like the app. Idempotent if already initialized.
    org.diylc.appframework.miscutils.ConfigurationManager.getInstance().initialize("diylc");
    return EDT.call(() -> {
      MainFrame frame = new MainFrame();
      frame.setVisible(true);
      return frame;
    });
  }

  private static void disposeFrame(MainFrame frame) {
    EDT.run(() -> {
      // Remove MainFrame's own window listeners first so dispose() doesn't run its save-prompt /
      // exit path — the end_session force/modified check has already decided.
      for (WindowListener wl : frame.getWindowListeners()) {
        frame.removeWindowListener(wl);
      }
      frame.dispose();
    });
  }
}
