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
package io.vidocq.grimm.cdi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * On the module path Cassini finds a generated descriptor through {@code provides}: the module declares every
 * one cassini-processor listed in {@code META-INF/services} (vidocq-workspace#40, item 5). Grimm has no
 * {@code @Provider} class, so the descriptors it generates are the adapter and the routes of
 * {@link OpenApiResource}.
 */
class CassiniDescriptorsProvidesTest {

    private static ModuleDescriptor module() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("target/classes/module-info.class"))) {
            return ModuleDescriptor.read(in);
        }
    }

    private static void assertProvidesWhatIsListed(String service) throws IOException {
        Set<String> listed = Set.copyOf(Files.readAllLines(Path.of("target/classes/META-INF/services/" + service)));
        Set<String> provided = module().provides().stream()
                .filter(p -> p.service().equals(service))
                .flatMap(p -> p.providers().stream())
                .collect(Collectors.toSet());

        assertFalse(listed.isEmpty(), service);
        assertEquals(listed, provided, service);
    }

    @Test
    void theModuleProvidesTheAdapterOfItsResource() throws IOException {
        assertProvidesWhatIsListed("io.vidocq.cassini.spi.gen.ResourceAdapter");
    }

    @Test
    void theModuleProvidesTheRoutesOfItsResource() throws IOException {
        assertProvidesWhatIsListed("io.vidocq.cassini.spi.gen.RouteProvider");
    }

    @Test
    void noProviderDescriptorIsListedWhenNoProviderClassExists() {
        // No @Provider class here: nothing to declare for ProviderDescriptor. If one is added, cassini-processor
        // lists its descriptor and this test must grow a provides check like the two above.
        assertFalse(Files.exists(
                Path.of("target/classes/META-INF/services/io.vidocq.cassini.spi.gen.ProviderDescriptor")));
    }
}
