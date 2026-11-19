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
package io.vidocq.runtime.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every service a runtime module declares in its {@code module-info.java} is also listed in its
 * {@code META-INF/services}: on the class path a {@code provides} clause is ignored, so an extension without the file is
 * never found there, by an Arquillian runner as by the tests of an application (BUG-20261117-10).
 */
class ClassPathServicesTest {

    private static final String MODULE_INFO = "module-info.class";

    /** A runtime module of the class path: its descriptor, and the root its {@code module-info.class} sits in. */
    private record RuntimeModule(ModuleDescriptor descriptor, String root) {

        /** The providers the module's {@code META-INF/services} file lists for {@code service}, none without one. */
        Set<String> listed(String service) throws IOException {
            Set<String> providers = new TreeSet<>();
            try (InputStream in = URI.create(root + "META-INF/services/" + service).toURL().openStream()) {
                new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(line -> line.split("#", 2)[0].strip())
                        .filter(line -> !line.isEmpty())
                        .forEach(providers::add);
            } catch (FileNotFoundException _) {
                // No services file: the module lists nothing for that service.
            }
            return providers;
        }
    }

    /** The modules named {@code io.vidocq.runtime…} whose descriptor the test class path holds. */
    private static List<RuntimeModule> runtimeModules() throws IOException {
        List<RuntimeModule> modules = new ArrayList<>();
        for (URL url : Collections.list(ClassPathServicesTest.class.getClassLoader().getResources(MODULE_INFO))) {
            try (InputStream in = url.openStream()) {
                ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                if (descriptor.name().startsWith("io.vidocq.runtime")) {
                    String location = url.toString();
                    modules.add(new RuntimeModule(descriptor,
                            location.substring(0, location.length() - MODULE_INFO.length())));
                }
            }
        }
        return modules;
    }

    @Test
    void theRuntimeModulesAreRead() throws IOException {
        // Guards the rule below: a class path without the runtime's descriptors would make it pass.
        List<String> names = runtimeModules().stream().map(module -> module.descriptor().name()).toList();

        assertTrue(names.contains("io.vidocq.runtime.core"), names::toString);
        assertTrue(names.contains("io.vidocq.runtime.extensions.jakartaee.web.mansart.pool"), names::toString);
    }

    @Test
    void everyServiceAModuleProvidesIsListedForTheClassPath() throws IOException {
        List<String> unlisted = new ArrayList<>();
        for (RuntimeModule module : runtimeModules()) {
            for (ModuleDescriptor.Provides provides : module.descriptor().provides()) {
                Set<String> listed = module.listed(provides.service());
                provides.providers().stream()
                        .filter(provider -> !listed.contains(provider))
                        .forEach(provider -> unlisted.add(
                                module.descriptor().name() + ": " + provides.service() + " with " + provider));
            }
        }

        assertEquals(List.of(), unlisted,
                "each provider goes in src/main/resources/META-INF/services/<service> of its module");
    }
}
