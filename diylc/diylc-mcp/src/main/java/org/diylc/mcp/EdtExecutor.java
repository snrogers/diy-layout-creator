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

import java.util.concurrent.Callable;
import javax.swing.SwingUtilities;

/**
 * Runs work on the Swing Event Dispatch Thread when the server is headed, or inline when headless.
 *
 * <p>In headed mode the {@code Presenter} is shared with a live {@code MainFrame}, so every access to
 * it — read or write — must happen on the EDT (Swing is not thread-safe). Wrapping a whole MCP tool
 * call in one {@link #call} also makes that operation atomic against the human's UI events: the human
 * cannot interleave actions inside a single {@code invokeAndWait} runnable.
 *
 * <p>In headless mode there is no EDT to honour, so work runs directly on the caller's thread.
 */
public final class EdtExecutor {

  private final boolean onEdt;

  private EdtExecutor(boolean onEdt) {
    this.onEdt = onEdt;
  }

  /** Marshal onto the EDT (headed mode). */
  public static EdtExecutor edt() {
    return new EdtExecutor(true);
  }

  /** Run inline on the calling thread (headless mode). */
  public static EdtExecutor direct() {
    return new EdtExecutor(false);
  }

  public <T> T call(Callable<T> task) {
    if (!onEdt || SwingUtilities.isEventDispatchThread()) {
      return callDirect(task);
    }
    final Object[] result = new Object[1];
    final Throwable[] failure = new Throwable[1];
    try {
      SwingUtilities.invokeAndWait(() -> {
        try {
          result[0] = task.call();
        } catch (Throwable t) {
          failure[0] = t;
        }
      });
    } catch (Exception e) {
      throw new RuntimeException("EDT dispatch failed", e);
    }
    if (failure[0] != null) {
      throw asRuntime(failure[0]);
    }
    @SuppressWarnings("unchecked")
    T typed = (T) result[0];
    return typed;
  }

  public void run(Runnable task) {
    call(() -> {
      task.run();
      return null;
    });
  }

  private static <T> T callDirect(Callable<T> task) {
    try {
      return task.call();
    } catch (Exception e) {
      throw asRuntime(e);
    }
  }

  private static RuntimeException asRuntime(Throwable t) {
    return t instanceof RuntimeException re ? re : new RuntimeException(t);
  }
}
