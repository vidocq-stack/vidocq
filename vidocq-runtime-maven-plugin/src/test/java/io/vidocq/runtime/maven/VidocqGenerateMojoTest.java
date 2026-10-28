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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code vidocq:generate} leaves under {@code target/} for the project's dependencies. */
class VidocqGenerateMojoTest {

    @TempDir
    Path dir;

    private Path target() {
        return dir.resolve("target");
    }

    @Test
    void withoutAClassesDirectoryNoEnrichedCopyIsLeft() throws Exception {
        Path stale = target().resolve(EnrichedJars.DIR_NAME).resolve("old.jar");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");

        mojo(plainJar()).execute();

        assertFalse(Files.exists(stale), "a copy of an earlier run goes");
    }

    @Test
    void aDependencyThatIsNoBeanArchiveIsNotEnriched() throws Exception {
        Files.createDirectories(target().resolve("classes"));
        Path stale = target().resolve(EnrichedJars.DIR_NAME).resolve("old.jar");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");

        mojo(plainJar()).execute();

        assertFalse(Files.exists(stale), "nothing scanned: no enriched copy stays");
        assertTrue(Files.isRegularFile(target().resolve("classes").resolve(
                io.vidocq.runtime.core.GenerateStamp.PATH)), "the stamp is written all the same");
    }

    @Test
    void aBeanArchiveDependencyGetsAnEnrichedCopy() throws Exception {
        Files.createDirectories(target().resolve("classes"));
        Path lib = dir.resolve("lib-1.0.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(lib), manifest())) {
            out.putNextEntry(new ZipEntry("META-INF/beans.xml"));
            out.write("<beans bean-discovery-mode=\"all\"/>".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("org/dep/Greeter.class"));
            out.write(beanClass("org.dep.Greeter"));
            out.closeEntry();
        }

        mojo(artifact("lib", lib)).execute();

        assertTrue(Files.isRegularFile(EnrichedJars.resolve(target(), lib)), "the scanned jar has its copy");
    }

    private Artifact plainJar() throws IOException {
        Path jar = dir.resolve("plain-1.0.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest())) {
            out.putNextEntry(new ZipEntry("org/plain/Plain.class"));
            out.write(beanClass("org.plain.Plain"));
            out.closeEntry();
        }
        return artifact("plain", jar);
    }

    private VidocqGenerateMojo mojo(Artifact... artifacts) throws Exception {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        project.setArtifacts(new LinkedHashSet<>(List.of(artifacts)));
        VidocqGenerateMojo mojo = new VidocqGenerateMojo();
        set(mojo, "project", project);
        set(mojo, "outputDirectory", target().resolve("classes").toFile());
        set(mojo, "buildDirectory", target().toFile());
        set(mojo, "autoScan", true);
        set(mojo, "cacheDir", dir.resolve("cache").toString());
        return mojo;
    }

    private static Artifact artifact(String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact("org.dep", artifactId, "1.0", "compile", "jar", "",
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }

    private static void set(VidocqGenerateMojo mojo, String field, Object value) throws Exception {
        Field f = VidocqGenerateMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
    }

    /** A public class with a public no-argument constructor. */
    private static byte[] beanClass(String name) {
        return ClassFile.of().build(ClassDesc.of(name), cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.aload(0)
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                        MethodTypeDesc.of(ConstantDescs.CD_void))
                                .return_()));
    }
}
