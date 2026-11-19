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
package io.vidocq.runtime.tooling;

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

    // -------------------------------------------------------------------------
    // One block of tool-written lines (BUG-20261031-14, tutorial T3)
    // -------------------------------------------------------------------------

    private static final String HEADER = ModuleInfoEditor.REFLECTION_HEADER;

    /** The packages opened for reflection go in one block, last, each line marked so the tools recognise it. */
    @Test
    void openingForReflectionTwiceExtendsTheOneBlock() {
        String once = ModuleInfoEditor.openForReflection(BARE, List.of("com.acme.todo.book"));
        String twice = ModuleInfoEditor.openForReflection(once, List.of("com.acme.todo.loan", "com.acme.todo.book"));

        assertEquals("""
                module com.acme.todo {
                    requires io.vidocq.runtime.core;

                    %s
                    opens com.acme.todo.book; // vidocq:reflection
                    opens com.acme.todo.loan; // vidocq:reflection
                }
                """.formatted(HEADER), twice);
    }

    /** An extension added after a fix goes with the other extension lines, before the block. */
    @Test
    void anExtensionAddedAfterAFixGoesBeforeTheBlock() {
        String fixed = ModuleInfoEditor.openForReflection(
                ModuleInfoEditor.add(BARE, "cassini-rest", List.of("requires jakarta.ws.rs")).source(),
                List.of("com.acme.todo.book"));

        String added = ModuleInfoEditor.add(fixed, "knock-health",
                List.of("requires io.vidocq.runtime.extensions.microprofile.knock")).source();

        assertEquals("""
                module com.acme.todo {
                    requires io.vidocq.runtime.core;
                    requires jakarta.ws.rs; // vidocq:cassini-rest
                    requires io.vidocq.runtime.extensions.microprofile.knock; // vidocq:knock-health

                    %s
                    opens com.acme.todo.book; // vidocq:reflection
                }
                """.formatted(HEADER), added);
    }

    /**
     * A file written before, with several headers and extension lines between them (the tutorial's step 11), is
     * tidied on the next write: one header, the extension lines together, the opened packages marked.
     */
    @Test
    void severalOldBlocksAreMergedOnTheNextWrite() {
        String old = """
                module com.acme.todo {
                    requires io.vidocq.runtime.core;
                    requires jakarta.ws.rs; // vidocq:cassini-rest

                    %1$s
                    opens com.acme.todo.book;
                    requires jakarta.data; // vidocq:mansart-data

                    %1$s
                    opens com.acme.todo.loan;

                    // added by vidocq:complete-module-info
                    opens com.acme.todo.support;
                }
                """.formatted(HEADER);

        String tidied = ModuleInfoEditor.add(old, "knock-health",
                List.of("requires io.vidocq.runtime.extensions.microprofile.knock")).source();

        assertEquals("""
                module com.acme.todo {
                    requires io.vidocq.runtime.core;
                    requires jakarta.ws.rs; // vidocq:cassini-rest
                    requires jakarta.data; // vidocq:mansart-data
                    requires io.vidocq.runtime.extensions.microprofile.knock; // vidocq:knock-health

                    %s
                    opens com.acme.todo.book; // vidocq:reflection
                    opens com.acme.todo.loan; // vidocq:reflection
                    opens com.acme.todo.support; // vidocq:reflection
                }
                """.formatted(HEADER), tidied);
    }

    /** Removing an extension leaves the opened packages, which no extension owns, and a hand-written line alone. */
    @Test
    void removingAnExtensionKeepsTheBlock() {
        String src = ModuleInfoEditor.openForReflection(
                ModuleInfoEditor.add(BARE, "cassini-rest", List.of("requires jakarta.ws.rs")).source(),
                List.of("com.acme.todo.book"));

        String removed = ModuleInfoEditor.remove(src, "cassini-rest", "jakarta.ws.rs").source();

        assertEquals("""
                module com.acme.todo {
                    requires io.vidocq.runtime.core;

                    %s
                    opens com.acme.todo.book; // vidocq:reflection
                }
                """.formatted(HEADER), removed);
    }

    /** A package the developer opened by hand is not opened again, nor moved. */
    @Test
    void aPackageOpenedByHandIsLeftWhereItIs() {
        String src = BARE.replace("requires io.vidocq.runtime.core;",
                "requires io.vidocq.runtime.core;\n    opens com.acme.todo.book;");

        assertEquals(src, ModuleInfoEditor.openForReflection(src, List.of("com.acme.todo.book")));
    }

    // An open module (`open module app { … }`) opens every package: an `opens` line inside it does not compile.

    private static final String OPEN = """
            /* the application */
            open module com.acme.todo {
                requires jakarta.ws.rs; // vidocq:cassini-rest
            }
            """;

    @Test
    void anOpenModuleIsRecognisedWhateverPrecedesIt() {
        assertTrue(ModuleInfoEditor.isOpenModule(OPEN));
        assertTrue(ModuleInfoEditor.isOpenModule("import a.B;\n@Deprecated\nopen   module x {\n}\n"));
        assertFalse(ModuleInfoEditor.isOpenModule("module x {\n    opens x.y; // open module once\n}\n"));
        assertFalse(ModuleInfoEditor.isOpenModule("// open module x\nmodule x {\n}\n"));
    }

    @Test
    void anExtensionAddsItsRequiresButNoOpensToAnOpenModule() {
        var result = ModuleInfoEditor.add(OPEN, "flyway-migration",
                List.of("requires org.flywaydb.core", "opens db.migration"));

        assertTrue(result.changed());
        assertTrue(result.source().contains("requires org.flywaydb.core; // vidocq:flyway-migration"), result.source());
        assertFalse(result.source().contains("opens "), result.source());
    }

    @Test
    void anOpenModuleNeedsNoOpensAndIsNeverGivenOne() {
        assertEquals(List.of("requires org.flywaydb.core"), ModuleInfoEditor.missing(OPEN,
                List.of("opens db.migration", "requires org.flywaydb.core", "requires jakarta.ws.rs")));
        assertEquals(OPEN, ModuleInfoEditor.openForReflection(OPEN, List.of("com.acme.todo.book")));
    }

    @Test
    void openingTheModuleDropsEveryOpensLineAndNamesTheHandWrittenOnes() {
        String src = """
                module com.acme.todo {
                    requires jakarta.ws.rs; // vidocq:cassini-rest
                    opens com.acme.todo; // vidocq:cassini-rest
                    opens com.acme.todo.legacy;

                    // added by vidocq: the runtime reaches these packages by reflection
                    opens com.acme.todo.book; // vidocq:reflection
                }
                """;

        var opened = ModuleInfoEditor.toOpenModule(src);

        assertTrue(ModuleInfoEditor.isOpenModule(opened.source()), opened.source());
        assertFalse(opened.source().contains("opens "), opened.source());
        assertFalse(opened.source().contains("added by vidocq"), opened.source());
        assertTrue(opened.source().contains("requires jakarta.ws.rs; // vidocq:cassini-rest"), opened.source());
        assertEquals(List.of("com.acme.todo.legacy"), opened.handWritten());
        assertEquals(3, opened.removed().size(), opened.removed().toString());
    }

    @Test
    void anOpenModuleWithOpensLinesHasThemDroppedToo() {
        String src = "open module x {\n    requires y;\n    opens x.z;\n}\n";

        var opened = ModuleInfoEditor.toOpenModule(src);

        assertEquals("open module x {\n    requires y;\n}\n", opened.source());
        assertEquals(List.of("x.z"), opened.handWritten());
    }
}
