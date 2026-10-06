/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.cli.ext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleInfoEditorTest {

    private static final String BARE = """
            module com.acme.todo {
                requires io.vidocq.runtime.core;
            }
            """;

    @Test
    void addsTheExtensionDirectivesBeforeTheClosingBraceWithAMarker() {
        ModuleInfoEditor.Result r = ModuleInfoEditor.add(BARE, "knock-health",
                List.of("requires io.vidocq.runtime.extensions.microprofile.knock"));

        assertTrue(r.changed());
        assertEquals("""
                module com.acme.todo {
                    requires io.vidocq.runtime.core;
                    requires io.vidocq.runtime.extensions.microprofile.knock; // vidocq:knock-health
                }
                """, r.source());
    }

    @Test
    void skipsADirectiveAlreadyDeclaredWhateverItsSpacing() {
        String src = BARE.replace("requires io.vidocq.runtime.core;", "requires   io.vidocq.runtime.core ;\n    requires jakarta.cdi;");

        ModuleInfoEditor.Result r = ModuleInfoEditor.add(src, "cassini-rest",
                List.of("requires jakarta.cdi", "requires jakarta.ws.rs"));

        assertEquals(1, r.source().split("requires jakarta.cdi").length - 1, "no duplicate, source was:\n" + r.source());
        assertTrue(r.source().contains("requires jakarta.ws.rs; // vidocq:cassini-rest"));
    }

    @Test
    void addingTwiceIsANoOp() {
        String once = ModuleInfoEditor.add(BARE, "knock-health", List.of("requires a.b")).source();

        ModuleInfoEditor.Result again = ModuleInfoEditor.add(once, "knock-health", List.of("requires a.b"));

        assertFalse(again.changed());
        assertEquals(once, again.source());
    }

    @Test
    void removeUndoesAddAndAlsoDropsAnUnmarkedRequiresOfTheExtensionModule() {
        String added = ModuleInfoEditor.add(BARE, "cassini-rest",
                List.of("requires jakarta.ws.rs", "requires io.vidocq.x.cassini")).source();
        assertEquals(BARE, ModuleInfoEditor.remove(added, "cassini-rest", "io.vidocq.x.cassini").source());

        String handWritten = BARE.replace("}", "    requires io.vidocq.x.cassini;\n}");
        assertEquals(BARE, ModuleInfoEditor.remove(handWritten, "cassini-rest", "io.vidocq.x.cassini").source());
    }

    @Test
    void removeLeavesOtherExtensionsAndHandWrittenLinesAlone() {
        String src = ModuleInfoEditor.add(BARE, "knock-health", List.of("requires k")).source()
                .replace("}", "    requires jakarta.ws.rs;\n}");

        ModuleInfoEditor.Result r = ModuleInfoEditor.remove(src, "cassini-rest", "io.vidocq.x.cassini");

        assertFalse(r.changed());
        assertEquals(src, r.source());
    }
}
