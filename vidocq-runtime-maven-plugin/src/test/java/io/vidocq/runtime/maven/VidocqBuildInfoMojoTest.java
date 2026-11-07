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

import io.vidocq.runtime.core.banner.BuildInfo;
import io.vidocq.runtime.maven.buildinfo.GitStatus;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#380: {@code vidocq:build-info} writes {@code META-INF/vidocq/build-info/<artifactId>.properties} into
 * the output directory, the file {@code git-commit-id-maven-plugin} wrote under the parent, from {@code .git} alone.
 */
class VidocqBuildInfoMojoTest {

    private static final String COMMIT = "9beafc47e1c8685e0123456789abcdef01234567";

    @TempDir
    Path dir;

    @Test
    void writesTheBuildIdentityOfTheProject() throws Exception {
        repository(dir);
        VidocqBuildInfoMojo mojo = mojo(project("todo", "1.0.0-SNAPSHOT", "jar"), null);

        mojo.execute();

        String written = Files.readString(dir.resolve("target/classes").resolve(BuildInfo.resource("todo")));
        assertTrue(written.contains("git.commit.id.abbrev=9beafc47\n"), written);
        assertTrue(written.contains("git.commit.id.full=" + COMMIT + "\n"), written);
        assertTrue(written.contains("git.branch=main\n"), written);
        assertTrue(written.contains("git.build.version=1.0.0-SNAPSHOT\n"), written);
        assertTrue(written.contains("git.build.time="), written);
        assertFalse(written.contains("git.dirty"), "no git command reachable: the flag is left out\n" + written);
        assertEquals("9beafc47", BuildInfo.read(null, null, dir.resolve("target/classes").toUri()).commit());
    }

    @Test
    void withoutARepositoryTheBuildStillSucceedsAsUnversioned() throws Exception {
        VidocqBuildInfoMojo mojo = mojo(project("todo", "1.0", "jar"), null);

        mojo.execute();

        String written = Files.readString(dir.resolve("target/classes").resolve(BuildInfo.resource("todo")));
        assertTrue(written.contains("git.commit.id.abbrev=unversionned\n"), written);
        assertTrue(written.contains("git.build.time="), written);
    }

    /** Reproducible builds: the project's {@code project.build.outputTimestamp} is the build time. */
    @Test
    void aReproducibleBuildTakesTheOutputTimestamp() throws Exception {
        repository(dir);
        VidocqBuildInfoMojo mojo = mojo(project("todo", "1.0.0", "jar"), "2026-01-02T03:04:05Z");

        mojo.execute();

        String written = Files.readString(dir.resolve("target/classes").resolve(BuildInfo.resource("todo")));
        assertTrue(written.contains("git.build.time=2026-01-02T03:04:05Z\n"), written);
    }

    @Test
    void aPomModuleAndASkipWriteNothing() throws Exception {
        repository(dir);
        mojo(project("aggregator", "1.0", "pom"), null).execute();
        VidocqBuildInfoMojo skipped = mojo(project("todo", "1.0", "jar"), null);
        set(skipped, "skip", true);
        skipped.execute();

        assertFalse(Files.exists(dir.resolve("target/classes/META-INF")), "nothing is written");
    }

    private MavenProject project(String artifactId, String version, String packaging) {
        Model model = new Model();
        model.setGroupId("com.acme");
        model.setArtifactId(artifactId);
        model.setVersion(version);
        model.setPackaging(packaging);
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve("pom.xml").toFile());
        return project;
    }

    /** A mojo on {@code project}, writing under {@code target/classes}, with no git command to run. */
    private VidocqBuildInfoMojo mojo(MavenProject project, String outputTimestamp) throws Exception {
        VidocqBuildInfoMojo mojo = new VidocqBuildInfoMojo();
        set(mojo, "project", project);
        set(mojo, "outputDirectory", dir.resolve("target/classes").toFile());
        set(mojo, "outputTimestamp", outputTimestamp);
        set(mojo, "status", GitStatus.NONE);
        return mojo;
    }

    private static void set(VidocqBuildInfoMojo mojo, String field, Object value) throws Exception {
        Field f = VidocqBuildInfoMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }

    private static void repository(Path root) throws IOException {
        Files.createDirectories(root.resolve(".git/refs/heads"));
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".git/refs/heads/main"), COMMIT + "\n", StandardCharsets.UTF_8);
    }
}
