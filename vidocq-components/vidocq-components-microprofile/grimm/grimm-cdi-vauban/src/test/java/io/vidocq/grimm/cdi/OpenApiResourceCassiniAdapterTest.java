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

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Cassini adapter of {@link OpenApiResource} is built with the module, so that a Cassini stack serving the
 * OpenAPI document does not generate it at run time (vidocq-workspace#38). On another JAX-RS runtime the class is
 * present but never loaded.
 */
class OpenApiResourceCassiniAdapterTest {

    @Test
    void theAdapterIsBuiltWithTheModule() {
        String file = OpenApiResource.class.getName().replace('.', '/') + "$$CassiniAdapter.class";
        assertNotNull(OpenApiResource.class.getClassLoader().getResource(file),
                () -> file + " is not in the module: cassini-processor did not run on it");
    }
}
