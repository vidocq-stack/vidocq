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

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module descriptor of cervantes-cdi-vauban declares the read edges its code needs: the claim
 * resolver maps claims to JSON-P values (MicroProfile JWT 2.2 §"Injection of JSON Web Token claims", JSON-P types), so the module
 * reads {@code jakarta.json} itself instead of relying on cervantes-core, which reads it
 * non-transitively (cervantes#21).
 */
class ModuleDescriptorTest {

    private static ModuleDescriptor descriptor() {
        return ModuleFinder.of(Path.of("target/classes")).find("io.vidocq.cervantes.cdi.vauban").orElseThrow().descriptor();
    }

    @Test
    void readsJakartaJson() {
        assertTrue(descriptor().requires().stream().anyMatch(r -> r.name().equals("jakarta.json")),
                () -> "io.vidocq.cervantes.cdi.vauban must require jakarta.json, requires = " + descriptor().requires());
    }
}
