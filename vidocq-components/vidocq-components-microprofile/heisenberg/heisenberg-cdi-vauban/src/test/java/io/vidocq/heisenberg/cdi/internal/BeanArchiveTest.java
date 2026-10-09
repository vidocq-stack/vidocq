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
package io.vidocq.heisenberg.cdi.internal;

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
 * The Fault Tolerance interceptors and the state-registry and metrics-recorder beans live in this
 * jar. It is an explicit bean archive so a container that does not scan implicit archives (Weld SE
 * by default) still discovers them; otherwise {@code @Retry}, {@code @Timeout} and the other
 * annotations silently do nothing (heisenberg#25).
 */
class BeanArchiveTest {

    private static final Path CLASSES = Path.of("target/classes");

    @Test
    void isAnAnnotatedBeanArchive() throws IOException {
        Path beansXml = CLASSES.resolve("META-INF/beans.xml");
        assertTrue(Files.isRegularFile(beansXml), () -> "missing " + beansXml);
        assertTrue(Files.readString(beansXml).contains("bean-discovery-mode=\"annotated\""),
                () -> beansXml + " must declare bean-discovery-mode=\"annotated\"");
    }

    /** On a class path {@code provides} is ignored, so every non-Vauban service is in {@code META-INF/services} too. */
    @Test
    void servicesFilesListWhatTheDescriptorProvides() throws IOException {
        ModuleDescriptor descriptor = ModuleFinder.of(CLASSES).find("io.vidocq.heisenberg.cdi.vauban").orElseThrow().descriptor();
        for (ModuleDescriptor.Provides p : descriptor.provides()) {
            if (p.service().startsWith("io.vidocq.vauban.")) continue; // only Vauban reads these, on the module path
            Path services = CLASSES.resolve("META-INF/services/" + p.service());
            assertTrue(Files.isRegularFile(services), () -> "missing " + services);
            List<String> listed = Files.readAllLines(services).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .sorted()
                    .toList();
            assertEquals(p.providers().stream().sorted().toList(), listed, p.service());
        }
    }
}
