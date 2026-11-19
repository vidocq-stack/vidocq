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
package io.vidocq.grimm.processor;

import io.vidocq.grimm.internal.OpenApiSerializers;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * grimm#19: the processor's main code uses grimm-core packages that grimm-core only exports to
 * named modules. As a module, the processor compiles only if grimm-core exports those packages to
 * {@code io.vidocq.grimm.processor} — and to nobody else beyond its existing friends.
 */
class GrimmCoreExportsTest {

    private static final String PROCESSOR = "io.vidocq.grimm.processor";

    private static ModuleDescriptor grimmCore() throws Exception {
        Path location = Path.of(OpenApiSerializers.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return ModuleFinder.of(location).find("io.vidocq.grimm.core")
                .orElseThrow(() -> new AssertionError("no io.vidocq.grimm.core descriptor in " + location))
                .descriptor();
    }

    @Test
    void exportsThePackagesTheProcessorUsesToTheProcessorOnly() throws Exception {
        Map<String, Set<String>> targets = grimmCore().exports().stream()
                .filter(e -> e.source().equals("io.vidocq.grimm.internal")
                        || e.source().equals("io.vidocq.grimm.internal.schema"))
                .collect(Collectors.toMap(ModuleDescriptor.Exports::source, ModuleDescriptor.Exports::targets));

        Set<String> friends = Set.of("io.vidocq.grimm.cdi.vauban", PROCESSOR);
        assertEquals(Map.of("io.vidocq.grimm.internal", friends, "io.vidocq.grimm.internal.schema", friends), targets);
    }
}
