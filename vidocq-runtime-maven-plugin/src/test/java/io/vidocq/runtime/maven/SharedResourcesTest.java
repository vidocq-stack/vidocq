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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resource directories several staged jars hold: a split package jlink refuses. {@code vidocq:jlink} names them, or,
 * with {@code vidocq.jlink.mergeSharedResources}, moves their files into one module of the image (vauban#163,
 * vidocq#256: Vaadin's flow-server and flow-react share {@code com/vaadin/flow/server/frontend/}).
 */
class SharedResourcesTest {

    @TempDir
    Path stage;

    /** An explicit module listing {@code packages} in {@code ModulePackages}, as vauban:modularize writes it. */
    static byte[] descriptor(String name, String... packages) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(name), mb -> {
            mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
            mb.moduleFlags(ClassFile.ACC_OPEN);
        }), clb -> clb.with(ModulePackagesAttribute.ofNames(
                java.util.Arrays.stream(packages).map(PackageDesc::of).toList())));
    }

    static byte[] emptyClass(String fqn) {
        return ClassFile.of().build(ClassDesc.of(fqn), cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
    }

    Path jar(String file, Map<String, byte[]> entries) throws IOException {
        Path jar = stage.resolve(file);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String entry(Path jar, String name) throws IOException {
        try (var in = new JarFile(jar.toFile())) {
            JarEntry e = in.getJarEntry(name);
            return e == null ? null : new String(in.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** flow-server: classes elsewhere, three files in frontend/; flow-react: one file there. */
    void vaadinLike() throws IOException {
        Map<String, byte[]> server = new LinkedHashMap<>();
        server.put("module-info.class", descriptor("flow.server", "com.vaadin.server"));
        server.put("com/vaadin/server/Server.class", emptyClass("com.vaadin.server.Server"));
        server.put("com/vaadin/frontend/Flow.tsx", text("flow"));
        server.put("com/vaadin/frontend/index.html", text("index"));
        server.put("com/vaadin/frontend/index-react.tsx", text("react index"));
        jar("flow-server-1.0.jar", server);
        Map<String, byte[]> react = new LinkedHashMap<>();
        react.put("module-info.class", descriptor("flow.react", "com.vaadin.react"));
        react.put("com/vaadin/react/React.class", emptyClass("com.vaadin.react.React"));
        react.put("com/vaadin/frontend/ReactAdapter.template", text("template"));
        jar("flow-react-1.0.jar", react);
    }

    @Test
    void findsAResourceDirectoryTwoJarsHold() throws IOException {
        vaadinLike();

        List<SharedResources.Shared> shared = SharedResources.find(stage);

        assertEquals(1, shared.size(), shared.toString());
        assertEquals("com/vaadin/frontend/", shared.getFirst().directory());
        assertEquals(List.of("flow-react-1.0.jar", "flow-server-1.0.jar"), shared.getFirst().jars());
    }

    /** Without the flag the build stops before jlink, with one line that names the jars, the directory and the flag. */
    @Test
    void theFailureNamesTheJarsTheDirectoryAndTheFlag() throws IOException {
        vaadinLike();

        String message = SharedResources.failureMessage(SharedResources.find(stage));

        assertTrue(message.contains("flow-react-1.0.jar and flow-server-1.0.jar both hold com/vaadin/frontend/"),
                message);
        assertTrue(message.contains("vidocq.jlink.mergeSharedResources=true"), message);
    }

    @Test
    void mergeMovesTheFilesIntoTheJarHoldingMostAndDeclaresThePackage() throws IOException {
        vaadinLike();

        List<String> log = SharedResources.merge(stage, SharedResources.find(stage));

        Path server = stage.resolve("flow-server-1.0.jar");
        Path react = stage.resolve("flow-react-1.0.jar");
        assertEquals("template", entry(server, "com/vaadin/frontend/ReactAdapter.template"));
        assertEquals("flow", entry(server, "com/vaadin/frontend/Flow.tsx"));
        assertNull(entry(react, "com/vaadin/frontend/ReactAdapter.template"));
        assertNotNull(entry(react, "com/vaadin/react/React.class"), "only the shared directory moves");
        ModuleDescriptor md = ModuleFinder.of(server).findAll().iterator().next().descriptor();
        assertEquals(Set.of("com.vaadin.server", "com.vaadin.frontend"), md.packages());
        assertEquals(List.of("merged com/vaadin/frontend/ into flow-server-1.0.jar (1 file from flow-react-1.0.jar)"),
                log);
        assertTrue(SharedResources.find(stage).isEmpty(), "nothing is shared any more");
    }

    /** The jar with classes in the package gets the files, whatever the counts: code never moves. */
    @Test
    void theJarWithClassesInThePackageGetsTheFiles() throws IOException {
        jar("a-1.0.jar", Map.of(
                "module-info.class", descriptor("a", "com.acme.pkg"),
                "com/acme/pkg/A.class", emptyClass("com.acme.pkg.A")));
        jar("b-1.0.jar", Map.of(
                "module-info.class", descriptor("b", "com.acme.b"),
                "com/acme/b/B.class", emptyClass("com.acme.b.B"),
                "com/acme/pkg/one.txt", text("1"),
                "com/acme/pkg/two.txt", text("2")));

        SharedResources.merge(stage, SharedResources.find(stage));

        assertEquals("1", entry(stage.resolve("a-1.0.jar"), "com/acme/pkg/one.txt"));
        assertNull(entry(stage.resolve("b-1.0.jar"), "com/acme/pkg/one.txt"));
    }

    @Test
    void twoFilesOfOnePathWithDifferentContentRefuseTheMerge() throws IOException {
        jar("a-1.0.jar", Map.of(
                "module-info.class", descriptor("a", "com.acme.a"),
                "com/acme/a/A.class", emptyClass("com.acme.a.A"),
                "com/acme/res/x.txt", text("from a")));
        jar("b-1.0.jar", Map.of(
                "module-info.class", descriptor("b", "com.acme.b"),
                "com/acme/b/B.class", emptyClass("com.acme.b.B"),
                "com/acme/res/x.txt", text("from b")));

        IOException e = assertThrows(IOException.class, () -> SharedResources.merge(stage, SharedResources.find(stage)));

        assertTrue(e.getMessage().contains("com/acme/res/x.txt"), e.getMessage());
    }

    @Test
    void classesInSeveralJarsAreNotMerged() throws IOException {
        jar("a-1.0.jar", Map.of(
                "module-info.class", descriptor("a", "com.acme.pkg"),
                "com/acme/pkg/A.class", emptyClass("com.acme.pkg.A")));
        jar("b-1.0.jar", Map.of(
                "module-info.class", descriptor("b", "com.acme.pkg"),
                "com/acme/pkg/B.class", emptyClass("com.acme.pkg.B")));

        assertTrue(SharedResources.find(stage).isEmpty(), "a class split package is not a shared resource directory");
    }
}
