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
package io.vidocq.runtime.cli.ext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectExtensionsTest {

    private static final String SPI = "io.vidocq.runtime.spi.VidocqExtension";

    @TempDir
    Path dir;

    @Test
    void namedModuleProvidingTheSpiIsAnExtension() throws IOException {
        Path jar = namedModuleJar("ext-named.jar", "com.acme.ext", "com.acme.ext.AcmeExtension", true);

        assertTrue(ProjectExtensions.providesExtension(jar));
    }

    @Test
    void namedModuleWithoutTheSpiIsNotAnExtension() throws IOException {
        Path jar = namedModuleJar("lib-named.jar", "com.acme.lib", "com.acme.lib.Util", false);

        assertFalse(ProjectExtensions.providesExtension(jar));
    }

    @Test
    void automaticModuleDeclaringTheServiceFileIsAnExtension() throws IOException {
        Path jar = jar("ext-auto.jar", Map.of(
                "META-INF/services/" + SPI, "com.acme.auto.AutoExtension\n".getBytes(StandardCharsets.UTF_8),
                "com/acme/auto/AutoExtension.class", new byte[0]));

        assertTrue(ProjectExtensions.providesExtension(jar));
    }

    @Test
    void plainJarAndMissingFileAreNotExtensions() throws IOException {
        Path plain = jar("plain.jar", Map.of("com/acme/plain/Plain.class", new byte[0]));

        assertFalse(ProjectExtensions.providesExtension(plain));
        assertFalse(ProjectExtensions.providesExtension(dir.resolve("missing.jar")));
    }

    @Test
    void detectsDirectAndTransitiveExtensionsAndNamesThemFromTheCatalog() throws IOException {
        Path cassini = namedModuleJar("cassini.jar", "x.cassini", "x.cassini.CassiniExtension", true);
        Path chappe = namedModuleJar("chappe.jar", "x.chappe", "x.chappe.ChappeExtension", true);
        Path thirdParty = namedModuleJar("third.jar", "x.third", "x.third.ThirdExtension", true);
        Path core = namedModuleJar("core.jar", "x.core", "x.core.Core", false);

        var cassiniArtifact = new ResolvedArtifact("io.vidocq.runtime.extensions.jakartaee.core",
                "vidocq-runtime-cassini-rest-extension", "0.4.0", cassini);
        var chappeArtifact = new ResolvedArtifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", "0.4.0", chappe);
        var thirdArtifact = new ResolvedArtifact("com.acme", "acme-extension", "1.0", thirdParty);
        var coreArtifact = new ResolvedArtifact("io.vidocq.runtime", "vidocq-runtime-core", "0.4.0", core);

        List<ProjectExtensions.Installed> installed = ProjectExtensions.detect(
                List.of(coreArtifact, cassiniArtifact, chappeArtifact, thirdArtifact),
                List.of(new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-core"),
                        new ExtensionCoordinate("io.vidocq.runtime.extensions.jakartaee.core",
                                "vidocq-runtime-cassini-rest-extension")));

        assertEquals(List.of(
                new ProjectExtensions.Installed("cassini-rest", cassiniArtifact, true),
                new ProjectExtensions.Installed("chappe-webserver", chappeArtifact, false),
                new ProjectExtensions.Installed("acme-extension", thirdArtifact, false)), installed);
    }

    @Test
    void cacheAnswersUntilThePomOrASnapshotChanges() throws IOException {
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path snapshotJar = Files.writeString(dir.resolve("lib-1.0-SNAPSHOT.jar"), "v1");
        var installed = new ProjectExtensions.Installed("cassini-rest",
                new ResolvedArtifact("g", "a", "1.0", dir.resolve("a.jar")), true);
        Path cacheFile = dir.resolve("cache/project.cache");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, ExtensionCache.write(new ExtensionCache.Entry(
                ExtensionCache.hash("<project/>"),
                List.of(ExtensionCache.fingerprint(snapshotJar)),
                List.of(installed))));

        assertEquals(java.util.Optional.of(List.of(installed)),
                ProjectExtensions.fromCache(project, cacheFile));

        Files.writeString(snapshotJar, "v2, re-downloaded");
        assertTrue(ProjectExtensions.fromCache(project, cacheFile).isEmpty());
    }

    private Path namedModuleJar(String name, String module, String clazz, boolean provides)
            throws IOException {
        ClassDesc type = ClassDesc.of(clazz);
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(module), m -> {
            m.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
            if (provides) {
                m.provides(ClassDesc.of(SPI), type);
            }
        }));
        return jar(name, Map.of(
                "module-info.class", moduleInfo,
                clazz.replace('.', '/') + ".class", new byte[0]));
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new JarEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return jar;
    }
}
