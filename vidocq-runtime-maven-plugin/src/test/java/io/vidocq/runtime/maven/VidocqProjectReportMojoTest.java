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

import io.vidocq.runtime.tooling.DoctorContext;
import io.vidocq.runtime.tooling.KnownExtensions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq:doctor} and {@code vidocq:list-extensions}, the reports of {@code vidocq doctor} and {@code vidocq extension list} (Vidocq/vidocq#314). */
class VidocqProjectReportMojoTest {

    private static final String CASSINI_GROUP = "io.vidocq.runtime.extensions.jakartaee.core";
    private static final String CASSINI = "vidocq-runtime-cassini-rest-extension";

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();

    @Test
    void listExtensionsShowsTheInstalledOnesTransitiveOnesMarked() throws Exception {
        MavenProject project = project();

        mojo(new VidocqListExtensionsMojo(), project).execute();

        assertEquals("info: Installed extensions:", lines.getFirst());
        assertTrue(lines.contains("info:   ✔ cassini-rest               " + CASSINI_GROUP + ":" + CASSINI + ":0.4.0"),
                lines.toString());
        assertTrue(lines.contains("info:   ✔ chappe-webserver           io.vidocq.runtime.extensions.essentials:"
                + "vidocq-runtime-chappe-webserver-extension:0.4.0  (transitive)"), lines.toString());
        assertEquals(3, lines.size(), "the core jar is no extension: " + lines);
    }

    @Test
    void listExtensionsAddsTheCatalogOnRequest() throws Exception {
        VidocqListExtensionsMojo mojo = mojo(new VidocqListExtensionsMojo(), project());
        set(mojo, "available", true);

        mojo.execute();

        assertTrue(lines.contains("info: Available extensions:"), lines.toString());
        assertEquals(KnownExtensions.catalog().size(),
                lines.stream().filter(l -> l.startsWith("info:     • ")).count(), lines.toString());
        // By family, as vidocq extension list --available (Vidocq/vidocq#353).
        assertEquals(List.of("info:   Jakarta EE", "info:   MicroProfile", "info:   Data", "info:   Web"),
                lines.stream().filter(l -> l.matches("info:   [A-Z][\\w ]+")).toList(), lines.toString());
        assertTrue(lines.getLast().contains("mvn vidocq:add-extension -Dextensions=<id>"), lines.getLast());
    }

    @Test
    void listExtensionsSaysHowToAddOneWhenThereIsNone() throws Exception {
        MavenProject project = new MavenProject(new Model());
        project.setArtifacts(Set.of());

        mojo(new VidocqListExtensionsMojo(), project).execute();

        assertEquals(List.of("info: Installed extensions:",
                "info:   (none — add one with mvn vidocq:add-extension -Dextensions=<id>)"), lines);
    }

    @Test
    void doctorCountsTheExtensionsOfTheProject() throws Exception {
        MavenProject project = project();
        Files.writeString(dir.resolve("pom.xml"), "<project><parent><groupId>io.vidocq.runtime</groupId></parent></project>");
        project.setFile(dir.resolve("pom.xml").toFile());

        mojo(new VidocqDoctorMojo(), project).execute();

        assertTrue(lines.stream().anyMatch(l -> l.matches("info: ✔ Extensions +2 extensions in the runtime dependencies")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("info: ✔ Vidocq project ")), lines.toString());
        assertTrue(lines.getLast().matches("info:   6 checks — \\d ok, \\d warning\\(s\\), 0 failed"), lines.getLast());
    }

    @Test
    void aFailedCheckFailsTheBuild() throws Exception {
        MavenProject project = project();
        VidocqDoctorMojo mojo = new VidocqDoctorMojo() {
            @Override
            DoctorContext context() {
                return new DoctorContext(21, "21.0.5", 25, null, false, true, true, true, 1);
            }
        };

        MojoFailureException failure = assertThrows(MojoFailureException.class, () -> mojo(mojo, project).execute());

        assertTrue(failure.getMessage().contains("vidocq:doctor"), failure.getMessage());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("error: ✘ Java version ")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("error:     ↳ Install a JDK 25+")), lines.toString());
    }

    /** A project declaring Cassini, which brings Chappe; the core jar provides no extension. */
    private MavenProject project() throws IOException {
        Model model = new Model();
        Dependency cassini = new Dependency();
        cassini.setGroupId(CASSINI_GROUP);
        cassini.setArtifactId(CASSINI);
        model.addDependency(cassini);
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve("pom.xml").toFile());
        Set<Artifact> artifacts = new LinkedHashSet<>();
        artifacts.add(artifact("io.vidocq.runtime", "vidocq-runtime-core", jar("core.jar", false)));
        artifacts.add(artifact(CASSINI_GROUP, CASSINI, jar("cassini.jar", true)));
        artifacts.add(artifact("io.vidocq.runtime.extensions.essentials", "vidocq-runtime-chappe-webserver-extension",
                jar("chappe.jar", true)));
        project.setArtifacts(artifacts);
        return project;
    }

    private static Artifact artifact(String groupId, String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "0.4.0", "compile", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }

    /** An automatic-module jar; {@code extension} gives it the service file of an extension. */
    private Path jar(String name, boolean extension) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            if (extension) {
                zip.putNextEntry(new JarEntry("META-INF/services/io.vidocq.runtime.spi.VidocqExtension"));
                zip.write("x.y.Ext\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new JarEntry("x/y/Ext.class"));
            zip.closeEntry();
        }
        return jar;
    }

    private <M extends org.apache.maven.plugin.AbstractMojo> M mojo(M mojo, MavenProject project) throws Exception {
        set(mojo, "project", project);
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                lines.add("info: " + content);
            }

            @Override
            public void warn(CharSequence content) {
                lines.add("warn: " + content);
            }

            @Override
            public void error(CharSequence content) {
                lines.add("error: " + content);
            }
        });
        return mojo;
    }

    /** Sets the field {@code name} of {@code target}, declared by its class or a superclass. */
    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException _) {
                // declared higher up
            }
        }
        throw new NoSuchFieldException(name);
    }
}
