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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage of {@link ModuleRequirementsCollector#applicableTo} — an opens applies only to a module that has the package. */
class ModuleRequirementsCollectorTest {

    @Test
    void opensAppliesOnlyWhenTheModuleContainsThePackage(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("db").resolve("migration"));
        Set<Requirement> required = new LinkedHashSet<>(List.of(
                Requirement.opens("db.migration", "Flyway"),
                Requirement.opens("absent.pkg", "x")));

        Set<Requirement> applicable = ModuleRequirementsCollector.applicableTo(required, out.toFile());

        assertTrue(applicable.contains(Requirement.opens("db.migration", "Flyway")),
                "an opens applies when the module has the package");
        assertFalse(applicable.contains(Requirement.opens("absent.pkg", "x")),
                "an opens for a package the module lacks must be dropped (e.g. an extension that "
                        + "depends on the migration extension but ships no db.migration)");
    }

    @Test
    void requiresAlwaysApplies(@TempDir Path out) {
        Set<Requirement> applicable = ModuleRequirementsCollector.applicableTo(
                Set.of(Requirement.requires("java.sql", "x")), out.toFile());
        assertTrue(applicable.contains(Requirement.requires("java.sql", "x")));
    }

    @Test
    void nullOutputDirectoryDropsOpens() {
        assertTrue(ModuleRequirementsCollector.applicableTo(
                Set.of(Requirement.opens("db.migration", "x")), null).isEmpty());
    }
}
