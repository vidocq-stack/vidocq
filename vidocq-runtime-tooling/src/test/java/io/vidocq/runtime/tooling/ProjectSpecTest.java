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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The defaults and the checks of {@code vidocq create} and {@code vidocq:create} (Vidocq/vidocq#314). */
class ProjectSpecTest {

    private static final List<String> NONE = List.of();

    @Test
    void theGroupIdAndThePackageDefault() {
        ProjectSpec spec = ProjectSpec.of("todo-api", null, null, List.of("cassini-rest"), null, false, "--package");

        assertEquals(new ProjectSpec("todo-api", "io.example", "io.example.todo.api", Set.of("cassini-rest"), null,
                false), spec);
    }

    @Test
    void aDerivedPackageThatIsNoJavaNameIsRefusedNamingTheToolsOption() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ProjectSpec.of("ft-030", null, null, NONE, null, false, "-Dpackage=<pkg>"));

        assertTrue(e.getMessage().contains("io.example.ft.030"), e.getMessage());
        assertTrue(e.getMessage().contains("derived"), e.getMessage());
        assertTrue(e.getMessage().endsWith("choose another with -Dpackage=<pkg>."), e.getMessage());
    }

    /** BUG-20261024-04: create wrote the dependency of an unknown id under a coordinate that does not exist. */
    @Test
    void anUnknownExtensionIdIsRefused() {
        List<String> typo = List.of("knock-helth");

        var e = assertThrows(IllegalArgumentException.class,
                () -> ProjectSpec.of("demo", null, null, typo, null, false, "--package"));

        assertTrue(e.getMessage().startsWith("unknown extension 'knock-helth': not in the Vidocq catalog."
                + " Did you mean: knock-health?"), e.getMessage());
    }

    @Test
    void anExplicitCoordinateIsAccepted() {
        ProjectSpec spec = ProjectSpec.of("demo", null, null, List.of("com.acme:acme-thing"), null, false, "--package");

        assertEquals(Set.of("com.acme:acme-thing"), spec.extensions());
    }

    @Test
    void anExplicitPackageMustBeAJavaNameToo() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ProjectSpec.of("demo", "com.acme", "com.acme.class", NONE, null, false, "--package"));

        assertTrue(e.getMessage().startsWith("'com.acme.class' is not a valid Java package name."), e.getMessage());
    }

    @Test
    void aProjectNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> ProjectSpec.of(" ", null, null, NONE, null, false, "-"));
    }

    @Test
    void packageNamesAreJavaIdentifiersThatAreNoKeywords() {
        assertTrue(ProjectSpec.isPackageName("io.example.ft030"));
        assertFalse(ProjectSpec.isPackageName("io.example.030"));
        assertFalse(ProjectSpec.isPackageName("io..example"));
        assertFalse(ProjectSpec.isPackageName("io.example.null"));
    }

    /** BUG-20261024-01: the extensions are written in the order given, the same on every run. */
    @Test
    void theExtensionsKeepTheOrderGiven() {
        List<String> ids = List.of("mansart-data", "cassini-rest", "knock-health", "ravel-config", "foy-servlet");

        ProjectSpec spec = ProjectSpec.of("demo", null, null, ids, null, false, "-");

        assertEquals(ids, List.copyOf(spec.extensions()));
        assertEquals(ids, List.copyOf(new ProjectSpec("demo", "g", "g.demo", spec.extensions(), null, false).extensions()));
    }

    /** {@code vidocq create -x humboldt-telemetry -x mansart-pool} gets the JDBC spans glue too (BUG-20261030-25). */
    @Test
    void aProjectWithTelemetryAndThePoolGetsTheirCompanion() {
        ProjectSpec spec = ProjectSpec.of("library", null, null, List.of("humboldt-telemetry", "mansart-pool"), null,
                false, "--package");

        assertEquals(List.of("humboldt-telemetry", "mansart-pool", "jdbc-telemetry"),
                List.copyOf(spec.extensions()));
    }
}
