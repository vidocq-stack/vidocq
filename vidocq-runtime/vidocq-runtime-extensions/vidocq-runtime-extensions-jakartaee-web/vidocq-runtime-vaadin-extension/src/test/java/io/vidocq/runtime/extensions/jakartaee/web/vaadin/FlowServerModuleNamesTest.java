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
package io.vidocq.runtime.extensions.jakartaee.web.vaadin;

import com.vaadin.flow.di.LookupInitializer;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extension reads {@code flow-server} under both names its Vaadin versions give it: {@code flow.server},
 * derived from the jar's name up to Vaadin 25.3, and {@code com.vaadin.flow.server}, its
 * {@code Automatic-Module-Name} from vaadin/flow#26731 on. Each case puts this module, the real {@code flow-server}
 * jar under one of its names and the Servlet API in a layer, and loads {@link ModuleLookupInitializer}, whose
 * superclass is in {@code flow-server}: the class loads only if the extension's module reads it.
 */
class FlowServerModuleNamesTest {

    private static final String MODULE = "io.vidocq.runtime.extensions.jakartaee.web.vaadin";

    @Test
    void theNameDerivedFromTheJarIsRead(@TempDir Path dir) throws Exception {
        Path jar = Files.copy(location(LookupInitializer.class), dir.resolve("flow-server-25.3.4.jar"));
        assertRead(jar, "flow.server");
    }

    @Test
    void theAutomaticModuleNameIsRead(@TempDir Path dir) throws Exception {
        Path jar = withAutomaticModuleName(location(LookupInitializer.class), dir.resolve("flow-server-25.4.0.jar"),
                "com.vaadin.flow.server");
        assertRead(jar, "com.vaadin.flow.server");
    }

    private static void assertRead(Path flowServer, String name) throws Exception {
        ModuleFinder finder = ModuleFinder.of(location(ModuleLookupInitializer.class), flowServer,
                location(ServletException.class));
        Configuration cf = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), Set.of(MODULE, name));
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(cf, ClassLoader.getPlatformClassLoader());
        Class<?> initializer = Class.forName(ModuleLookupInitializer.class.getName(), false, layer.findLoader(MODULE));
        Module vaadin = layer.findModule(name).orElseThrow();
        assertEquals(vaadin, initializer.getSuperclass().getModule());
        assertTrue(initializer.getModule().canRead(vaadin), () -> MODULE + " does not read " + name);
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** A copy of {@code jar} whose manifest names its module {@code name}, as a Vaadin with stable names ships it. */
    private static Path withAutomaticModuleName(Path jar, Path copy, String name) throws Exception {
        try (JarFile in = new JarFile(jar.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(copy), manifest(in, name))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().equals(JarFile.MANIFEST_NAME) || entry.getName().equals("META-INF/")) continue;
                out.putNextEntry(new JarEntry(entry.getName()));
                try (var data = in.getInputStream(entry)) {
                    data.transferTo(out);
                }
                out.closeEntry();
            }
        }
        return copy;
    }

    private static Manifest manifest(JarFile jar, String name) throws Exception {
        Manifest manifest = jar.getManifest() != null ? new Manifest(jar.getManifest()) : new Manifest();
        manifest.getMainAttributes().putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Automatic-Module-Name"), name);
        return manifest;
    }
}
