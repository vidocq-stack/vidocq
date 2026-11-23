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
package io.vidocq.foy.chappe;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code foy-chappe} on the module path without CDI (BUG-20261123-56): the module resolves, and a web application
 * deploys, with no {@code jakarta.cdi} module. Its POM leaves the CDI API {@code provided}, as {@code beanManager} is
 * optional; its {@code module-info} required {@code jakarta.cdi}, so an application without CDI did not start.
 *
 * <p>The surefire run of this module is on the class path, where a {@code requires} has no effect: the test resolves
 * the modules of its class path in a layer of its own, without the CDI API, and boots Foy there.</p>
 */
class FoyChappeWithoutCdiTest {

    private static final String FOY_CHAPPE = "io.vidocq.foy.chappe";

    @Test
    void foyChappeResolvesAndDeploysWithoutTheCdiModule() throws Throwable {
        ModuleFinder finder = ModuleFinder.of(modulesWithoutCdi().toArray(Path[]::new));
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), Set.of(FOY_CHAPPE));
        assertFalse(configuration.findModule("jakarta.cdi").isPresent(), "jakarta.cdi was left out");

        ModuleLayer layer = ModuleLayer.boot()
                .defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());
        ClassLoader loader = layer.findLoader(FOY_CHAPPE);
        assertEquals("from web.xml", deployAndReadInitParameter(loader));
    }

    /**
     * Boots Foy in the layer of {@code loader} with a {@code web.xml} holding one context parameter. Each call is
     * linked by name and type, as compiled code links it: {@code Class.getMethod} would load the types of every
     * method of the class, {@code BeanManager} included, which no caller of {@code FoyChappeBoot} does.
     */
    private static String deployAndReadInitParameter(ClassLoader loader) throws Throwable {
        byte[] webXml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <context-param><param-name>p</param-name><param-value>from web.xml</param-value></context-param>
            </web-app>""".getBytes(StandardCharsets.UTF_8);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> boot = loader.loadClass(FOY_CHAPPE + ".FoyChappeBoot");
        Class<?> builderType = loader.loadClass(FOY_CHAPPE + ".FoyChappeBoot$Builder");
        Class<?> mountedType = loader.loadClass(FOY_CHAPPE + ".FoyChappeBoot$Mounted");
        Class<?> contextType = loader.loadClass("jakarta.servlet.ServletContext");

        Object builder = lookup.findStatic(boot, "builder", MethodType.methodType(builderType)).invoke();
        lookup.findVirtual(builderType, "classLoader", MethodType.methodType(builderType, ClassLoader.class))
                .invoke(builder, loader);
        lookup.findVirtual(builderType, "discoverPluggability", MethodType.methodType(builderType, boolean.class))
                .invoke(builder, false);
        lookup.findVirtual(builderType, "contextPath", MethodType.methodType(builderType, String.class))
                .invoke(builder, "/");
        lookup.findVirtual(builderType, "webXml", MethodType.methodType(builderType, InputStream.class))
                .invoke(builder, new ByteArrayInputStream(webXml));
        Optional<?> built = (Optional<?>) lookup.findVirtual(builderType, "build", MethodType.methodType(Optional.class))
                .invoke(builder);
        Object mounted = built.orElseThrow();
        try (AutoCloseable _ = (AutoCloseable) mounted) {
            Object context = lookup.findVirtual(mountedType, "servletContext", MethodType.methodType(contextType))
                    .invoke(mounted);
            return (String) lookup.findVirtual(contextType, "getInitParameter",
                    MethodType.methodType(String.class, String.class)).invoke(context, "p");
        }
    }

    /** The modular entries of the test class path, but the CDI API and its language model. */
    private static List<Path> modulesWithoutCdi() {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<Path> kept = new ArrayList<>();
        for (String entry : classPath.split(File.pathSeparator)) {
            Path path = Path.of(entry);
            boolean candidate = Files.isDirectory(path)
                    ? Files.exists(path.resolve("module-info.class"))
                    : entry.endsWith(".jar") && Files.exists(path);
            if (candidate && !leftOut(path)) {
                kept.add(path);
            }
        }
        return kept;
    }

    /** Whether {@code path} is left out: a module of the CDI API, or a jar no module can be read from. */
    private static boolean leftOut(Path path) {
        try {
            return ModuleFinder.of(path).findAll().stream()
                    .map(ModuleReference::descriptor)
                    .anyMatch(d -> d.name().startsWith("jakarta.cdi"));
        } catch (FindException _) {
            return true;
        }
    }
}
