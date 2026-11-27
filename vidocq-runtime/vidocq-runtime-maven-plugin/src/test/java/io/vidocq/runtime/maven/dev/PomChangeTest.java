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
package io.vidocq.runtime.maven.dev;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Vidocq/vidocq#799, BUG-20261127-09: what a {@code vidocq:dev} session does when its pom changed. */
class PomChangeTest {

    private static final String POM = "<project><!-- v1 --></project>";

    @Test
    void anUnchangedPomIsNeverResolvedAgain(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        AtomicInteger resolved = new AtomicInteger();
        PomChange change = new PomChange(pom, project(dir, "core"), () -> {
            resolved.incrementAndGet();
            return project(dir, "core");
        });

        assertInstanceOf(PomChange.Same.class, change.check());
        assertEquals(0, resolved.get(), "the same text: no resolution, the reload goes on at once");
    }

    @Test
    void anAddedExtensionRestartsTheSessionOnTheNewDependenciesAndSaysWhy(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        MavenProject added = project(dir, "core", "grimm-openapi-extension", "grimm-openapi-ui-extension");
        PomChange change = new PomChange(pom, project(dir, "core"), () -> added);

        Files.writeString(pom, "<project><!-- v2 --></project>");
        PomChange.Restart restart = assertInstanceOf(PomChange.Restart.class, change.check());

        assertSame(added, restart.project());
        assertEquals("pom.xml changed the dependencies (added grimm-openapi-extension, grimm-openapi-ui-extension):"
                + " restarting the application on the new ones.", restart.line());
    }

    @Test
    void aPomChangeThatLeavesTheDependenciesAloneReloadsAsBefore(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        AtomicInteger resolved = new AtomicInteger();
        PomChange change = new PomChange(pom, project(dir, "core"), () -> {
            resolved.incrementAndGet();
            return project(dir, "core");
        });

        Files.writeString(pom, "<project><properties><a>b</a></properties></project>");
        assertInstanceOf(PomChange.Same.class, change.check());
        assertInstanceOf(PomChange.Same.class, change.check());
        assertEquals(1, resolved.get(), "the new text is the one the session runs on: resolved once");
    }

    @Test
    void dependenciesThatDoNotResolveHoldTheReloadUntilThePomIsFixed(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        List<Boolean> fails = new ArrayList<>(List.of(true, true, false));
        MavenProject fixed = project(dir, "core", "knock-health-extension");
        PomChange change = new PomChange(pom, project(dir, "core"), () -> {
            if (fails.removeFirst()) {
                throw new PomChange.ResolutionFailedException("Could not find artifact com.example:nope:1.0", null);
            }
            return fixed;
        });

        Files.writeString(pom, "<project><!-- nope --></project>");
        PomChange.Hold hold = assertInstanceOf(PomChange.Hold.class, change.check());
        assertEquals("pom.xml changed, but its dependencies do not resolve (Could not find artifact"
                + " com.example:nope:1.0): the application keeps running on the previous ones, without this change."
                + " Fix pom.xml, or restart vidocq dev.", hold.line());
        assertInstanceOf(PomChange.Hold.class, change.check(), "every later recompile tries again");
        assertSame(fixed, assertInstanceOf(PomChange.Restart.class, change.check()).project());
    }

    @Test
    void aRemovedExtensionAndAChangeOfVersionAreNamedAsSuch(@TempDir Path dir) {
        MavenProject before = project(dir, "core", "a", "b", "c", "d", "e");
        MavenProject after = project(dir, "core", "f");
        assertEquals("added f; removed a, b, c and 2 more", PomChange.changes(before, after));
        assertEquals("the resolved dependencies changed", PomChange.changes(before, project(dir, "core", "a", "b",
                "c", "d", "e")));
    }

    @Test
    void anotherVersionOfTheSameDependencyIsAnotherResolution(@TempDir Path dir) {
        Artifact one = artifact(dir, "core", "1.0");
        Artifact two = artifact(dir, "core", "2.0");
        assertEquals(PomChange.keys(List.of(one)), PomChange.keys(List.of(artifact(dir, "core", "1.0"))));
        assertNotEquals(PomChange.keys(List.of(one)), PomChange.keys(List.of(two)));
    }

    /** What a recompile does once the pom was looked at: a reload, or the line of a restart or of a hold. */
    @Test
    void aRestartAndAHoldEachSayWhyAndStopTheReload(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        MavenProject added = project(dir, "core", "knock-health-extension");
        List<Boolean> fails = new ArrayList<>(List.of(true, false));
        PomChange change = new PomChange(pom, project(dir, "core"), () -> {
            if (fails.removeFirst()) {
                throw new PomChange.ResolutionFailedException("offline", null);
            }
            return added;
        });
        List<String> info = new ArrayList<>();
        List<String> warn = new ArrayList<>();
        List<MavenProject> restarts = new ArrayList<>();

        assertTrue(change.reloads(restarts::add, info::add, warn::add), "the same pom: a reload");
        Files.writeString(pom, "<project><!-- v2 --></project>");
        assertFalse(change.reloads(restarts::add, info::add, warn::add));
        assertEquals(1, warn.size(), warn.toString());
        assertFalse(change.reloads(restarts::add, info::add, warn::add));
        assertEquals(List.of(added), restarts);
        assertEquals(List.of("pom.xml changed the dependencies (added knock-health-extension): restarting the"
                + " application on the new ones."), info);
    }

    /** A project that declares and resolves {@code artifactIds}. */
    private static MavenProject project(Path dir, String... artifactIds) {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        LinkedHashSet<Artifact> artifacts = new LinkedHashSet<>();
        for (String artifactId : artifactIds) {
            Dependency dependency = new Dependency();
            dependency.setGroupId("io.vidocq.runtime");
            dependency.setArtifactId(artifactId);
            model.addDependency(dependency);
            artifacts.add(artifact(dir, artifactId, "0.4.1"));
        }
        MavenProject project = new MavenProject(model);
        project.setArtifacts(artifacts);
        return project;
    }

    private static Artifact artifact(Path dir, String artifactId, String version) {
        Artifact artifact = new DefaultArtifact("io.vidocq.runtime", artifactId, version, "runtime", "jar", "",
                new DefaultArtifactHandler("jar"));
        artifact.setFile(dir.resolve(artifactId + "-" + version + ".jar").toFile());
        return artifact;
    }
}
