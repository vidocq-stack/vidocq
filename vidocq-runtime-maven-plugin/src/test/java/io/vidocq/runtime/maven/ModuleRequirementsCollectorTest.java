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
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * An extension that ships no {@link io.vidocq.runtime.codegen.commons.ModuleRequirementsDescriptor#RESOURCE}
     * descriptor -- which is all but two of them -- still states its {@code requires} in the extension catalog,
     * and the check has to see them: Cassini does not re-export Jakarta REST, so an application that uses it
     * has to require {@code jakarta.ws.rs} itself.
     */
    @Test
    void catalogRequiresAreCollectedForAnExtensionWithoutADescriptor(@TempDir Path empty) {
        Artifact cassini = artifact("io.vidocq.runtime.extensions.jakartaee.core",
                "vidocq-runtime-cassini-rest-extension", empty);

        Set<Requirement> collected = ModuleRequirementsCollector.collect(Set.of(cassini), new SystemStreamLog());

        assertEquals(
                List.of("io.vidocq.cassini.api", "jakarta.cdi", "jakarta.inject", "jakarta.json.bind",
                        "jakarta.ws.rs"),
                collected.stream().filter(r -> r.kind() == Requirement.Kind.REQUIRES)
                        .map(Requirement::name).sorted().toList(),
                "the catalog's module-info.requires of cassini-rest must be collected");
        assertTrue(collected.stream().allMatch(r -> !r.reason().isBlank()),
                "every requirement carries a reason, so the goals can explain the fix");
    }

    @Test
    void anArtifactOutsideTheCatalogContributesNothing(@TempDir Path empty) {
        Artifact plain = artifact("org.example", "not-an-extension", empty);
        assertTrue(ModuleRequirementsCollector.collect(Set.of(plain), new SystemStreamLog()).isEmpty());
    }

    /** {@code requires static} stays out: it is a separate catalog key that {@link Requirement} cannot express. */
    @Test
    void catalogRequiresStaticIsNotReportedAsAPlainRequires(@TempDir Path empty) {
        Artifact mansartData = artifact("io.vidocq.runtime.extensions.jakartaee.web",
                "vidocq-runtime-mansart-data-extension", empty);

        Set<Requirement> collected = ModuleRequirementsCollector.collect(Set.of(mansartData), new SystemStreamLog());

        assertFalse(collected.contains(Requirement.requires("java.compiler", "")),
                "mansart-data declares java.compiler as requires static, which must not become a plain requires");
        assertTrue(collected.stream().noneMatch(r -> "java.compiler".equals(r.name())));
    }

    private static Artifact artifact(String groupId, String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "0.4.0", "compile", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }
}
