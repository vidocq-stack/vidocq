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

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** vidocq#232: {@code vidocq:generate} compiles the application's JSP pages with Ibarra. */
class IbarraPagePrecompilationTest {

    private static final String PAGE_INDEX = "io.vidocq.ibarra.spi.PageIndex";
    private static final Set<String> MODULES = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core",
            "io.vidocq.peano.api", "io.vidocq.peano.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el");

    @TempDir Path work;

    private final List<String> warnings = new ArrayList<>();

    private Path classes() { return work.resolve("classes"); }

    /** Compiles {@code module-info.java} for module {@code consumer.pages} into {@code classes}. */
    private void module(String... requires) throws Exception {
        Path source = Files.createDirectories(work.resolve("src"));
        var text = new StringBuilder("module consumer.pages {");
        for (String r : requires) text.append(" requires ").append(r).append(';');
        Files.writeString(source.resolve("module-info.java"), text.append(" }").toString());
        Files.createDirectories(classes());
        var errors = new java.io.ByteArrayOutputStream();
        int status = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "--module-path", join(modulePath()), "-d", classes().toString(), source.resolve("module-info.java").toString());
        assertEquals(0, status, errors.toString());
    }

    private void resource(String path, String text) throws Exception {
        Path file = classes().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private ModuleDescriptor descriptor() throws Exception {
        try (var in = Files.newInputStream(classes().resolve("module-info.class"))) {
            return ModuleDescriptor.read(in);
        }
    }

    private static Set<String> providers(ModuleDescriptor module, String service) {
        return module.provides().stream().filter(p -> p.service().equals(service))
                .flatMap(p -> p.providers().stream()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void an_application_without_pages_is_left_alone() throws Exception {
        module("io.vidocq.ibarra.api");
        byte[] before = Files.readAllBytes(classes().resolve("module-info.class"));

        assertEquals(0, IbarraPagePrecompilation.apply(classes(), modulePath(), warnings::add));

        assertEquals(List.of(), warnings);
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(classes().resolve("module-info.class"))));
    }

    @Test
    void the_pages_under_meta_inf_resources_are_compiled_and_indexed() throws Exception {
        module("io.vidocq.ibarra.api");
        resource("META-INF/resources/hello.jsp", "<%= \"hello\" %>");
        resource("META-INF/resources/admin/panel.jsp", "<%= \"panel\" %>");

        assertEquals(2, IbarraPagePrecompilation.apply(classes(), modulePath(), warnings::add));

        assertEquals(List.of(), warnings);
        Set<String> index = providers(descriptor(), PAGE_INDEX);
        assertEquals(1, index.size(), "one generated page index: " + index);
        assertTrue(Files.isRegularFile(classes().resolve(index.iterator().next().replace('.', '/') + ".class")));
    }

    /** A forward target kept under the classes root's WEB-INF, out of the client's reach, is a page too. */
    @Test
    void a_page_under_web_inf_is_compiled_too() throws Exception {
        module("io.vidocq.ibarra.api");
        resource("WEB-INF/views/greet.jsp", "<%= \"greet\" %>");

        assertEquals(1, IbarraPagePrecompilation.apply(classes(), modulePath(), warnings::add));

        assertEquals(List.of(), warnings);
        assertFalse(providers(descriptor(), PAGE_INDEX).isEmpty());
    }

    @Test
    void pages_without_the_ibarra_api_requirement_are_not_compiled_and_named_in_a_warning() throws Exception {
        module();
        resource("META-INF/resources/hello.jsp", "<%= \"hello\" %>");

        assertEquals(0, IbarraPagePrecompilation.apply(classes(), modulePath(), warnings::add));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("requires io.vidocq.ibarra.api"), warnings.getFirst());
        assertTrue(providers(descriptor(), PAGE_INDEX).isEmpty());
    }

    @Test
    void a_page_that_does_not_translate_fails_the_build() throws Exception {
        module("io.vidocq.ibarra.api");
        resource("META-INF/resources/broken.jsp", "<%@ page import=\"no.such.Type\" %><%= new Type() %>");

        var failure = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> IbarraPagePrecompilation.apply(classes(), modulePath(), warnings::add));

        assertTrue(failure.getMessage().contains("/broken.jsp") || String.valueOf(failure.getCause()).contains("broken"),
                String.valueOf(failure));
    }

    /** The mojo compiles the pages first, then registers Peano's accessors: both services survive. */
    @Test
    void the_peano_registration_after_the_pages_keeps_the_page_index() throws Exception {
        module("io.vidocq.ibarra.api", "io.vidocq.peano.api");
        resource("META-INF/resources/el.jsp", "${param.x}");

        assertEquals(1, IbarraPagePrecompilation.apply(classes(), modulePath(), warnings::add));
        PeanoAccessorRegistration.apply(classes());

        assertFalse(providers(descriptor(), PAGE_INDEX).isEmpty(), "the page index survives the Peano rewrite");
    }

    /** The explicit modules of the test class path the pages compile against. */
    private static List<Path> modulePath() throws Exception {
        var jars = new ArrayList<Path>();
        for (String item : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!item.endsWith(".jar")) continue;
            try (var jar = new JarFile(item)) {
                if (jar.getJarEntry("module-info.class") != null) jars.add(Path.of(item));
            }
        }
        return ModuleFinder.of(jars.toArray(Path[]::new)).findAll().stream()
                .filter(r -> MODULES.contains(r.descriptor().name()))
                .map(r -> Path.of(r.location().orElseThrow())).sorted().toList();
    }

    private static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }
}
