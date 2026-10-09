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
package io.vidocq.cervantes.cdi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jar works on a class path too, where {@code provides} clauses are ignored: Weld, an
 * application server, or any WAR (cervantes#24). The CDI extension is therefore also listed in
 * {@code META-INF/services}, and the jar is an explicit bean archive so a container that does not
 * scan implicit archives (Weld SE by default) still discovers the producers and the request context.
 */
class ClassPathRegistrationTest {

    private static final String BCE = "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";
    private static final Path CLASSES = Path.of("target/classes");

    @Test
    void servicesFileListsTheExtensionsTheDescriptorProvides() throws IOException {
        ModuleDescriptor descriptor = ModuleFinder.of(CLASSES).find("io.vidocq.cervantes.cdi.vauban").orElseThrow().descriptor();
        List<String> provided = descriptor.provides().stream()
                .filter(p -> p.service().equals(BCE))
                .flatMap(p -> p.providers().stream())
                .sorted()
                .toList();
        Path services = CLASSES.resolve("META-INF/services/" + BCE);
        assertTrue(Files.isRegularFile(services), () -> "missing " + services);
        List<String> listed = Files.readAllLines(services).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .sorted()
                .toList();
        assertEquals(List.of("io.vidocq.cervantes.cdi.CervantesClaimExtension"), provided);
        assertEquals(provided, listed);
    }

    @Test
    void isAnAnnotatedBeanArchive() throws IOException {
        Path beansXml = CLASSES.resolve("META-INF/beans.xml");
        assertTrue(Files.isRegularFile(beansXml), () -> "missing " + beansXml);
        assertTrue(Files.readString(beansXml).contains("bean-discovery-mode=\"annotated\""),
                () -> beansXml + " must declare bean-discovery-mode=\"annotated\"");
    }
}
