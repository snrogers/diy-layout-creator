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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * The session state machine (ADR-0001). Headless-only — never opens a window, so it is CI-safe; the
 * headed path is exercised by the manual e2e-headed.sh script.
 */
public class SessionManagerTest {

  private SessionManager sessions;

  @Before
  public void setUp() {
    System.setProperty("java.awt.headless", "true");
    sessions = new SessionManager();
  }

  @Test
  public void bootsSessionLess() {
    assertFalse(sessions.hasSession());
    // Any tool that needs an engine must error until a session is open.
    assertThrows(IllegalStateException.class, sessions::requireEngine);
  }

  @Test
  public void startThenEndHeadless() {
    sessions.startSession(false);
    assertTrue(sessions.hasSession());
    assertNotNull(sessions.requireEngine());

    sessions.endSession(false);
    assertFalse(sessions.hasSession());
  }

  @Test
  public void startingWhenOpenErrors() {
    sessions.startSession(false);
    assertThrows(IllegalStateException.class, () -> sessions.startSession(false));
  }

  @Test
  public void endingWhenClosedErrors() {
    assertThrows(IllegalStateException.class, () -> sessions.endSession(false));
  }

  @Test
  public void headedWithoutDisplayErrors() {
    // Tests run headless, so requesting a headed session must fail clearly rather than hang.
    assertThrows(IllegalStateException.class, () -> sessions.startSession(true));
    assertFalse("a failed start must not leave a session open", sessions.hasSession());
  }

  @Test
  public void endRefusesUnsavedUnlessForced() {
    sessions.startSession(false);
    sessions.requireEngine().addComponent("Resistor", new int[][] {{60, 60}, {140, 60}});

    // Project is now modified — plain end is refused...
    assertThrows(IllegalStateException.class, () -> sessions.endSession(false));
    assertTrue("refused end must leave the session open", sessions.hasSession());

    // ...but force=true discards and closes.
    sessions.endSession(true);
    assertFalse(sessions.hasSession());
  }

  @Test
  public void sessionsAreReopenable() {
    sessions.startSession(false);
    sessions.endSession(false);
    sessions.startSession(false); // must not throw — server survives endSession
    assertTrue(sessions.hasSession());
  }
}
