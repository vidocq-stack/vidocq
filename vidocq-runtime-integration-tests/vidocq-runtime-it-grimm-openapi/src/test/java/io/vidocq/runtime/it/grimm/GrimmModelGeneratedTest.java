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
package io.vidocq.runtime.it.grimm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The OpenAPI extension's codegen bundle runs Grimm's processor in the application's build: each JAX-RS resource
 * gets a {@code $$GrimmModel} contribution, so the runtime does not scan it by reflection (vidocq-workspace#38).
 */
class GrimmModelGeneratedTest {

    @Test
    void theResourceHasItsCompileTimeContribution() {
        String file = GreetingResource.class.getName().replace('.', '/') + "$$GrimmModel.class";
        assertNotNull(GreetingResource.class.getClassLoader().getResource(file),
                () -> file + " was not generated: the grimm-openapi codegen bundle is not on the processor path");
    }
}
