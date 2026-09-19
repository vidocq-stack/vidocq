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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

import dev.langchain4j.cdi.mcp.server.transport.McpEndpoint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U1 of Vidocq/vidocq#94: the bean list this module ships, which {@code vidocq:generate} writes at build time from the
 * langchain4j-cdi jar, names exactly the server's beans, every one loadable, and nothing of this module.
 */
class McpBeanListTest {

    /** What makes a class of the langchain4j-cdi jar a bean on Vidocq: a scope, or a Jakarta REST role for Cassini. */
    private static final Set<String> BEAN_MARKERS = Set.of(
            "Ljakarta/enterprise/context/ApplicationScoped;",
            "Ljakarta/enterprise/context/RequestScoped;",
            "Ljakarta/enterprise/context/SessionScoped;",
            "Ljakarta/enterprise/context/Dependent;",
            "Ljakarta/inject/Singleton;",
            "Ljakarta/ws/rs/Path;",
            "Ljakarta/ws/rs/ext/Provider;");

    @Test
    void everyLineLoadsFromTheLangchain4jCdiJar() throws Exception {
        for (String line : shippedList()) {
            Class<?> type = Class.forName(line, false, McpEndpoint.class.getClassLoader());
            assertEquals(McpEndpoint.class.getModule(), type.getModule(), line);
        }
    }

    @Test
    void theListNamesTheEndpointTheRegistriesTheResolverAndTheProviders() throws Exception {
        Set<String> list = shippedList();
        for (String required : Set.of(
                "dev.langchain4j.cdi.mcp.server.transport.McpEndpoint",
                "dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry",
                "dev.langchain4j.cdi.mcp.server.registry.McpPromptRegistry",
                "dev.langchain4j.cdi.mcp.server.registry.McpResourceRegistry",
                "dev.langchain4j.cdi.mcp.server.transport.McpServerConfigResolver",
                "dev.langchain4j.cdi.mcp.server.transport.McpExceptionMapper",
                "dev.langchain4j.cdi.mcp.server.transport.McpListenRoutingFilter",
                "dev.langchain4j.cdi.mcp.server.transport.McpSseStreamHeadersFilter")) {
            assertTrue(list.contains(required), required + " missing from " + list);
        }
    }

    @Test
    void theListNamesNothingOfThisModuleSoTheConfigProducerStaysConditional() throws Exception {
        String ownPackage = McpExtension.class.getPackageName() + ".";
        assertTrue(shippedList().stream().noneMatch(line -> line.startsWith(ownPackage)), shippedList().toString());
    }

    @Test
    void theListIsWhatTheJarDeclares() throws Exception {
        assertEquals(beansOfTheJar(), shippedList());
    }

    /** The lines of this module's {@code META-INF/vauban-beans.list}, comments left out. */
    static Set<String> shippedList() throws IOException {
        try (InputStream in = McpExtension.class.getModule().getResourceAsStream("META-INF/vauban-beans.list")) {
            assertNotNull(in, "META-INF/vauban-beans.list is missing: vidocq:generate did not run");
            Set<String> lines = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    lines.add(stripped);
                }
            }
            return lines;
        }
    }

    /** The concrete classes of the langchain4j-cdi jar that carry one of {@link #BEAN_MARKERS}, read with the class-file API. */
    static Set<String> beansOfTheJar() throws Exception {
        Path jar = Path.of(McpEndpoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> beans = new TreeSet<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.getName().endsWith(".class") || entry.getName().endsWith("module-info.class")) {
                    continue;
                }
                ClassModel model;
                try (InputStream in = file.getInputStream(entry)) {
                    model = ClassFile.of().parse(in.readAllBytes());
                }
                boolean concrete = (model.flags().flagsMask() & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_INTERFACE)) == 0;
                boolean marked = model.findAttribute(Attributes.runtimeVisibleAnnotations())
                        .map(RuntimeVisibleAnnotationsAttribute::annotations)
                        .orElse(java.util.List.of())
                        .stream()
                        .anyMatch(annotation -> BEAN_MARKERS.contains(annotation.className().stringValue()));
                if (concrete && marked) {
                    beans.add(model.thisClass().asInternalName().replace('/', '.'));
                }
            }
        }
        return beans;
    }
}
