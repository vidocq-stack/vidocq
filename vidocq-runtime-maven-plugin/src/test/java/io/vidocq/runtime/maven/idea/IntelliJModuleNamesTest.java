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
package io.vidocq.runtime.maven.idea;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the module names IntelliJ IDEA gives the projects of a Maven build on a first import, which the
 * generated {@code <module name="..."/>} must match.
 *
 * <p>The expected names are those of IntelliJ IDEA 2026.2 ({@code MavenModuleNameMapper}, tag
 * {@code idea/262.10968.63}); the first two cases are IntelliJ's own {@code StructureImportingTest}
 * {@code testModulesWithSameArtifactId} and {@code testModulesWithSameArtifactIdAndGroup}.
 */
class IntelliJModuleNamesTest {

    @TempDir
    Path root;

    @Test
    void aModuleIsNamedAfterItsArtifactId() throws IOException {
        MavenProject parent = project("", "test", "project");
        MavenProject alpha = project("alpha", "test", "alpha-app");

        assertEquals(List.of("project", "alpha-app"), names(parent, alpha));
    }

    @Test
    void theSameArtifactIdInTwoGroupsGetsACounterAndItsGroup() throws IOException {
        MavenProject parent = project("", "test", "project");
        MavenProject first = project("dir1/m", "test.group1", "m");
        MavenProject second = project("dir2/m", "test.group2", "m");

        assertEquals(List.of("project", "m (1) (test.group1)", "m (2) (test.group2)"), names(parent, first, second));
    }

    @Test
    void theSameArtifactIdInOneGroupGetsACounterOnly() throws IOException {
        MavenProject parent = project("", "test", "project");
        MavenProject first = project("dir1/m", "test", "m");
        MavenProject second = project("dir2/m", "test", "m");

        assertEquals(List.of("project", "m (1)", "m (2)"), names(parent, first, second));
    }

    /** IDEA-320329: the comparison ignores case, and the counter follows the pom paths, not the reactor order. */
    @Test
    void artifactIdsThatDifferOnlyByCaseAreNumberedInPomPathOrder() throws IOException {
        MavenProject library = project("library", "t.lib", "Beta");
        MavenProject beta = project("apps/beta", "t.edge", "beta");

        assertEquals(List.of("Beta (2) (t.lib)", "beta (1) (t.edge)"), names(library, beta));
    }

    /** {@code MavenId.UNKNOWN_VALUE} is the one artifactId Maven accepts that IntelliJ does not take as a name. */
    @Test
    void anArtifactIdIntelliJDoesNotAcceptGivesTheDirectoryName() throws IOException {
        MavenProject unknown = project("server", "test", "Unknown");

        assertEquals(List.of("server"), names(unknown));
    }

    private List<String> names(MavenProject... projects) {
        List<MavenProject> all = List.of(projects);
        var names = IntelliJModuleNames.firstImport(all);
        return all.stream().map(IntelliJModuleNames::key).map(names::get).toList();
    }

    private MavenProject project(String directory, String groupId, String artifactId) throws IOException {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion("1");
        MavenProject project = new MavenProject(model);
        Path pom = Files.createDirectories(root.resolve(directory)).resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        project.setFile(pom.toFile());
        return project;
    }
}
