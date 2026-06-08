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
package io.vidocq.vauban.indexer.model;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

class DotNameCompatibilityTest {

    @Test
    void fromDescriptor_supportsRegularClassDescriptor() {
        DotName dotName = DotName.fromDescriptor("Lcom/example/Foo;");
        assertEquals("com.example.Foo", dotName.value());
    }

    @Test
    void fromDescriptor_supportsArrayClassDescriptor() {
        DotName dotName = DotName.fromDescriptor("[Lorg/eclipse/microprofile/openapi/apps/petstore/model/Pet;");
        assertEquals("org.eclipse.microprofile.openapi.apps.petstore.model.Pet", dotName.value());
    }
}


