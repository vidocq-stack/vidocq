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
package io.vidocq.vauban.maven.modularize;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModularizeMojoTest {

    @Test
    void unsetModeMeansDerived() throws Exception {
        assertEquals(Modularizer.Mode.DERIVED, ModularizeMojo.modeOf(null));
        assertEquals(Modularizer.Mode.DERIVED, ModularizeMojo.modeOf("  "));
    }

    @Test
    void bothModesAreAccepted() throws Exception {
        assertEquals(Modularizer.Mode.DERIVED, ModularizeMojo.modeOf("derived"));
        assertEquals(Modularizer.Mode.ALL_AUTOMATIC, ModularizeMojo.modeOf("all-automatic"));
        assertEquals(Modularizer.Mode.ALL_AUTOMATIC, ModularizeMojo.modeOf(" ALL-Automatic "));
    }

    /**
     * A typo used to fall through to {@code derived}: the build then quietly left every
     * {@code Automatic-Module-Name} jar unpatched and only failed later, in jlink, blaming the jars.
     */
    @Test
    void unknownModeFailsTheBuildNamingTheValue() {
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> ModularizeMojo.modeOf("all-automtic"));

        assertEquals("vauban:modularize: unknown mode 'all-automtic'"
                + " (expected derived | all-automatic)", e.getMessage());
    }

    /** {@code <uses><artifactId>a.B, c.D</artifactId></uses>}: commas, whitespace and line breaks all separate. */
    @Test
    void declaredUsesAreSplitPerArtifact() throws Exception {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("liquibase-core", " liquibase.change.Change,\n   liquibase.database.Database  liquibase.change.Change ");
        configured.put("acme", "*");

        assertEquals(Map.of("liquibase-core", Set.of("liquibase.change.Change", "liquibase.database.Database"),
                        "acme", Set.of("*")),
                ModularizeMojo.declaredUsesOf(configured));
        assertEquals(Map.of(), ModularizeMojo.declaredUsesOf(null));
    }

    @Test
    void aDeclaredUsesThatIsNoTypeNameFailsTheBuild() {
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> ModularizeMojo.declaredUsesOf(Map.of("acme", "com.acme.Ok, com.acme.Not-A-Type")));

        assertEquals("vauban:modularize: <uses> of acme: 'com.acme.Not-A-Type' is not a fully qualified type name",
                e.getMessage());
    }

    @Test
    void anEmptyDeclaredUsesFailsTheBuild() {
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> ModularizeMojo.declaredUsesOf(Map.of("acme", "  ")));

        assertEquals("vauban:modularize: <uses> of acme names no type", e.getMessage());
    }
}
