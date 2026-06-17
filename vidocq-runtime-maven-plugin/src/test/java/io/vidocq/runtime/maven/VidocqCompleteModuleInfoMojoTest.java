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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.codegen.commons.Requirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage of {@link VidocqCompleteModuleInfoMojo}'s module-info text editing. */
class VidocqCompleteModuleInfoMojoTest {

    private static final String MODULE_INFO = "module sample.app {\n    requires java.base;\n}\n";

    @Test
    void injectsMissingOpensBeforeClosingBrace() {
        List<String> add = VidocqCompleteModuleInfoMojo.directivesToAdd(
                MODULE_INFO, List.of(Requirement.opens("db.migration", "Flyway")));
        assertEquals(List.of("opens db.migration;"), add);

        String updated = VidocqCompleteModuleInfoMojo.inject(MODULE_INFO, add);
        assertTrue(updated.contains("opens db.migration;"), updated);
        assertTrue(updated.contains("added by vidocq:complete-module-info"), updated);
        // inserted before the module's closing brace
        assertTrue(updated.indexOf("opens db.migration;") < updated.lastIndexOf('}'), updated);
        // existing content preserved
        assertTrue(updated.contains("requires java.base;"), updated);
    }

    @Test
    void isIdempotent() {
        List<String> add = VidocqCompleteModuleInfoMojo.directivesToAdd(
                MODULE_INFO, List.of(Requirement.opens("db.migration", "Flyway")));
        String once = VidocqCompleteModuleInfoMojo.inject(MODULE_INFO, add);

        // second pass: the directive is now declared, so nothing more to add
        List<String> again = VidocqCompleteModuleInfoMojo.directivesToAdd(
                once, List.of(Requirement.opens("db.migration", "Flyway")));
        assertTrue(again.isEmpty(), "already-declared opens must not be re-added:\n" + once);
    }

    @Test
    void alreadyDeclaredMatchesModuloIndentation() {
        assertTrue(VidocqCompleteModuleInfoMojo.alreadyDeclared(
                "module m {\n        opens db.migration;\n}\n", "opens db.migration;"));
    }

    @Test
    void commentedOutDirectiveDoesNotCountAsDeclared() {
        assertFalse(VidocqCompleteModuleInfoMojo.alreadyDeclared(
                "module m {\n    // opens db.migration;\n}\n", "opens db.migration;"));
    }

    @Test
    void requiresIsNeverAutoAdded() {
        // a requires may be satisfied transitively; complete only injects opens
        List<String> add = VidocqCompleteModuleInfoMojo.directivesToAdd(
                MODULE_INFO, List.of(Requirement.requires("java.sql", "x")));
        assertTrue(add.isEmpty(), "complete must not inject a requires directive");
    }

    @Test
    void injectWithNoDirectivesLeavesSourceUntouched() {
        assertEquals(MODULE_INFO, VidocqCompleteModuleInfoMojo.inject(MODULE_INFO, List.of()));
    }
}
