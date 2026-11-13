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

import io.vidocq.runtime.maven.frozen.Greeting;
import io.vidocq.runtime.maven.frozen.GreetingCreator;
import io.vidocq.runtime.maven.frozen.GreetingExtension;
import io.vidocq.runtime.maven.frozen.Hello;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq:generate} freezes the build compatible extensions of the application (Vidocq/vauban#288): it runs
 * them on the path the application starts with and writes what they did in {@code target/classes}; a start on that
 * path loads the record instead of running them.
 */
class VidocqGenerateFreezeTest {

    private static final String SERVICE =
            "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    @TempDir
    Path dir;

    private Path classes;

    private static final class RecordingLog extends SystemStreamLog {
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            infos.add(content.toString());
        }

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }
    }

    /** A start's view of the application: its resources from its own path only, as a packaged application has. */
    private static final class ApplicationLoader extends URLClassLoader {
        ApplicationLoader(Path... path) throws IOException {
            super(urls(path), VidocqGenerateFreezeTest.class.getClassLoader());
        }

        private static URL[] urls(Path... path) throws IOException {
            URL[] urls = new URL[path.length];
            for (int i = 0; i < path.length; i++) {
                urls[i] = path[i].toUri().toURL();
            }
            return urls;
        }

        @Override
        public URL getResource(String name) {
            return findResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return findResources(name);
        }
    }

    /** The application: one bean, compiled and indexed as the annotation processor leaves it. */
    @BeforeEach
    void application() throws IOException {
        classes = Files.createDirectories(dir.resolve("target").resolve("classes"));
        Path hello = classes.resolve(Hello.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(hello.getParent());
        Files.write(hello, compiled(Hello.class));
        Files.createDirectories(classes.resolve("META-INF"));
        Files.writeString(classes.resolve("META-INF/vauban-beans.list"), Hello.class.getName() + "\n");
        GreetingExtension.RUNS.set(0);
    }

    @Test
    void theExtensionOfADependencyIsFrozenAndTheStartLoadsWhatItDid() throws Exception {
        Path extension = extensionJar("greeting-1.0.jar", false);
        RecordingLog log = new RecordingLog();

        mojo(log, true, artifact("greeting", extension, "compile")).execute();

        assertTrue(Files.isRegularFile(classes.resolve(FrozenExtensions.PATH)), log.warnings.toString());
        assertTrue(log.infos.contains("Vauban: froze 1 build compatible extension(s); the container start loads"
                + " what they did."), log.infos.toString());

        GreetingExtension.RUNS.set(0);
        try (var loader = new ApplicationLoader(classes, extension);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(0, GreetingExtension.RUNS.get(), "the start must not run the frozen extension");
            assertTrue(container.extensionsAtStart().frozen(), String.valueOf(container.extensionsAtStart().note()));
            assertEquals("hello from the build", container.select(Greeting.class).text());
            assertEquals("hello", container.select(Hello.class).id());
        }
    }

    @Test
    void aStartExtensionIsRecordedAsRunningAgain() throws Exception {
        Path extension = extensionJar("greeting-1.0.jar", false);
        RecordingLog log = new RecordingLog();
        VidocqGenerateMojo mojo = mojo(log, true, artifact("greeting", extension, "compile"));
        set(mojo, "startExtensions", List.of(GreetingExtension.class.getName()));

        mojo.execute();

        assertTrue(log.infos.contains("Vauban: 1 extension(s) run again at each start, having left work for it: "
                + GreetingExtension.class.getName() + "."), log.infos.toString());
        try (var loader = new ApplicationLoader(classes, extension);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(List.of(GreetingExtension.class.getName()), container.extensionsAtStart().ranAtStart());
            assertEquals("hello from the build", container.select(Greeting.class).text());
        }
    }

    @Test
    void onlyWhatALaunchShipsIsPartOfTheDeployment() throws Exception {
        RecordingLog log = new RecordingLog();

        // A provided jar, a test jar and a development-only jar are not on the path the application starts with.
        mojo(log, true, artifact("provided", extensionJar("provided-1.0.jar", false), "provided"),
                artifact("tests", extensionJar("tests-1.0.jar", false), "test"),
                artifact("console", extensionJar("console-1.0.jar", true), "compile")).execute();

        assertFalse(Files.exists(classes.resolve(FrozenExtensions.PATH)), "no extension is shipped: no record");
        assertTrue(log.infos.stream().anyMatch(info -> info.contains(FrozenExtensions.NO_EXTENSION)),
                log.infos.toString());
    }

    @Test
    void withoutTheParameterNoRecordIsWrittenAndAnEarlierOneGoes() throws Exception {
        Path extension = extensionJar("greeting-1.0.jar", false);
        mojo(new RecordingLog(), true, artifact("greeting", extension, "compile")).execute();
        assertTrue(Files.exists(classes.resolve(FrozenExtensions.PATH)));

        mojo(new RecordingLog(), false, artifact("greeting", extension, "compile")).execute();

        assertFalse(Files.exists(classes.resolve(FrozenExtensions.PATH)));
        assertFalse(Files.exists(classes.resolve(FrozenExtensions.ENHANCEMENTS_PATH)));
        assertFalse(Files.exists(classes.resolve(FrozenExtensions.SYNTHETIC_PATH)));
    }

    /** A jar that brings {@link GreetingExtension}, declared as a service, and what its synthetic bean needs. */
    private Path extensionJar(String name, boolean devOnly) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (devOnly) {
            manifest.getMainAttributes().putValue(DevOnlyJars.MANIFEST_ENTRY, "true");
        }
        Path jar = dir.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Class<?> type : List.of(GreetingExtension.class, Greeting.class, GreetingCreator.class)) {
                out.putNextEntry(new ZipEntry(type.getName().replace('.', '/') + ".class"));
                out.write(compiled(type));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(SERVICE));
            out.write((GreetingExtension.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static byte[] compiled(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    private VidocqGenerateMojo mojo(RecordingLog log, boolean freeze, Artifact... artifacts) throws Exception {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        project.setArtifacts(new LinkedHashSet<>(List.of(artifacts)));
        VidocqGenerateMojo mojo = new VidocqGenerateMojo();
        set(mojo, "project", project);
        set(mojo, "outputDirectory", classes.toFile());
        set(mojo, "buildDirectory", classes.getParent().toFile());
        set(mojo, "autoScan", false);
        set(mojo, "cacheDir", dir.resolve("cache").toString());
        set(mojo, "freezeExtensions", freeze);
        mojo.setLog(log);
        return mojo;
    }

    private static Artifact artifact(String artifactId, Path file, String scope) {
        Artifact artifact = new DefaultArtifact("org.dep", artifactId, "1.0", scope, "jar", "",
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }

    private static void set(VidocqGenerateMojo mojo, String field, Object value) throws Exception {
        Field f = VidocqGenerateMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }
}
