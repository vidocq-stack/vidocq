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
package io.vidocq.runtime.codegen.commons;

import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (no compiler) coverage of the {@link ModuleDescriptor}-based satisfaction check used by the
 * Maven goal, and of the {@link ModuleRequirementsDescriptor} properties parser.
 */
class ModuleRequirementsParsingTest {

    private static final Set<Requirement> REQUIRED = Set.of(
            Requirement.requires("java.sql", "holder implements javax.sql.DataSource"),
            Requirement.opens("db.migration", "Flyway scans classpath migrations"));

    @Test
    void reportsMissingRequiresAgainstCompiledDescriptor() {
        ModuleDescriptor d = ModuleDescriptor.newModule("app").opens("db.migration").build();
        List<Requirement> missing = ModuleInfoRequirements.missing(d, REQUIRED);
        assertEquals(1, missing.size());
        assertEquals("requires java.sql;", missing.getFirst().directive());
    }

    @Test
    void reportsMissingOpensAgainstCompiledDescriptor() {
        ModuleDescriptor d = ModuleDescriptor.newModule("app").requires("java.sql").build();
        List<Requirement> missing = ModuleInfoRequirements.missing(d, REQUIRED);
        assertEquals(1, missing.size());
        assertEquals("opens db.migration;", missing.getFirst().directive());
    }

    @Test
    void allDirectivesPresentLeavesNothingMissing() {
        ModuleDescriptor d = ModuleDescriptor.newModule("app")
                .requires("java.sql").opens("db.migration").build();
        assertTrue(ModuleInfoRequirements.missing(d, REQUIRED).isEmpty());
    }

    @Test
    void openModuleSatisfiesEveryOpens() {
        ModuleDescriptor d = ModuleDescriptor.newOpenModule("app").requires("java.sql").build();
        assertTrue(ModuleInfoRequirements.missing(d, REQUIRED).isEmpty(),
                "an `open module` opens every package, satisfying the opens requirement");
    }

    @Test
    void qualifiedOpensDoesNotSatisfyUnqualifiedRequirementOnDescriptor() {
        ModuleDescriptor d = ModuleDescriptor.newModule("app")
                .requires("java.sql").opens("db.migration", Set.of("some.other.module")).build();
        List<Requirement> missing = ModuleInfoRequirements.missing(d, REQUIRED);
        assertEquals(1, missing.size());
        assertEquals("opens db.migration;", missing.getFirst().directive());
    }

    @Test
    void parsesRequiresOpensAndReasons() {
        Properties p = new Properties();
        p.setProperty("requires", "java.sql, io.vidocq.runtime.extensions.essentials.migration");
        p.setProperty("opens", "db.migration");
        p.setProperty("reason.java.sql", "holder implements javax.sql.DataSource");
        p.setProperty("reason.db.migration", "Flyway scans classpath migrations");

        Set<Requirement> reqs = ModuleRequirementsDescriptor.parse(p);

        assertTrue(reqs.contains(Requirement.requires("java.sql", "holder implements javax.sql.DataSource")));
        assertTrue(reqs.contains(Requirement.requires(
                "io.vidocq.runtime.extensions.essentials.migration", "")));
        assertTrue(reqs.contains(Requirement.opens("db.migration", "Flyway scans classpath migrations")));
    }

    @Test
    void parsesQualifiedOpens() {
        Properties p = new Properties();
        p.setProperty("opens.to.io.vidocq.vauban.core", "io.app.beans");
        Set<Requirement> reqs = ModuleRequirementsDescriptor.parse(p);
        assertTrue(reqs.contains(Requirement.opensTo("io.app.beans", Set.of("io.vidocq.vauban.core"), "")));
    }

    @Test
    void emptyOrNullPropertiesYieldNoRequirements() {
        assertTrue(ModuleRequirementsDescriptor.parse(null).isEmpty());
        assertTrue(ModuleRequirementsDescriptor.parse(new Properties()).isEmpty());
    }
}
