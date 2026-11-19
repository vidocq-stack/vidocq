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
package io.vidocq.cervantes.jaxrs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * cassini-processor writes a descriptor for each of the module's providers, which Cassini reads at startup instead of
 * their annotations. On the module path Cassini finds them through {@code provides}: the module must declare every
 * one the processor listed in {@code META-INF/services}.
 */
class CassiniProviderDescriptorsTest {

    private static final String SERVICE = "io.vidocq.cassini.spi.gen.ProviderDescriptor";

    @Test
    void theModuleProvidesTheDescriptorOfEachProvider() throws IOException {
        List<String> listed = Files.readAllLines(Path.of("target/classes/META-INF/services/" + SERVICE));
        ModuleDescriptor module;
        try (InputStream in = Files.newInputStream(Path.of("target/classes/module-info.class"))) {
            module = ModuleDescriptor.read(in);
        }
        List<String> provided = module.provides().stream()
                .filter(p -> p.service().equals(SERVICE))
                .flatMap(p -> p.providers().stream())
                .toList();

        assertEquals(Set.of("io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter$$CassiniProvider",
                "io.vidocq.cervantes.jaxrs.RolesAllowedDynamicFeature$$CassiniProvider"), Set.copyOf(listed));
        assertEquals(Set.copyOf(listed), Set.copyOf(provided));
    }
}
