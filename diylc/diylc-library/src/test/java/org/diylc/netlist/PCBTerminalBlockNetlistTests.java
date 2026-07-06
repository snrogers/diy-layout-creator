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
package org.diylc.netlist;

import static org.junit.Assert.assertEquals;

import org.diylc.components.connectivity.PCBTerminalBlock;
import org.junit.Test;

public class PCBTerminalBlockNetlistTests {

  @Test
  public void positionsAreNamedNetlistNodes() {
    // Terminal-block positions must be named netlist nodes ("1".."N", the AbstractComponent
    // default, same as Pin Header). With null node names the block emitted no nodes, and
    // NetlistBuilder drops groups with <2 named nodes - so a net dead-ending at a terminal block
    // (off-board wiring) silently vanished from the extracted netlist, indistinguishable from a
    // missing connection.
    PCBTerminalBlock block = new PCBTerminalBlock();
    for (int i = 0; i < block.getControlPointCount(); i++) {
      assertEquals(String.valueOf(i + 1), block.getControlPointNodeName(i));
    }
  }
}
