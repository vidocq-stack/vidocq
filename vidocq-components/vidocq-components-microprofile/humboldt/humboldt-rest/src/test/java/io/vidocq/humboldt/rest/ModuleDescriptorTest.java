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
package io.vidocq.humboldt.rest;

import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * humboldt-rest reads the MicroProfile Rest Client API through the module Vidocq ships, cyrano's repackaged
 * {@code io.vidocq.cyrano.mp.rest.client.api}, never through an automatic module named after the upstream jar
 * (humboldt#18).
 */
class ModuleDescriptorTest {

    private static ModuleDescriptor descriptor() {
        return ModuleFinder.of(Path.of("target/classes")).find("io.vidocq.humboldt.rest").orElseThrow().descriptor();
    }

    @Test
    void readsTheRestClientApiThroughCyranosModule() {
        var restClient = descriptor().requires().stream()
                .filter(r -> r.name().contains("rest.client"))
                .collect(Collectors.toMap(ModuleDescriptor.Requires::name, ModuleDescriptor.Requires::modifiers));

        assertEquals(java.util.Map.of("io.vidocq.cyrano.mp.rest.client.api", Set.of(ModuleDescriptor.Requires.Modifier.STATIC)),
                restClient);
    }

    @Test
    void namesNoAutomaticModuleOfTheUpstreamApi() {
        assertFalse(descriptor().requires().stream().anyMatch(r -> r.name().equals("microprofile.rest.client.api")));
    }
}
