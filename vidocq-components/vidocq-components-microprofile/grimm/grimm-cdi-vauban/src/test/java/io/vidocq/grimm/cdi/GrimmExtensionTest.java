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

import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrimmExtensionTest {

    @Test
    void creatorResolvesTheRecordedNamesAndSkipsUnknownOnes() {
        var created = new ScannedTypesCreator().create(null, new Parameters() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(String key, Class<T> type) {
                return (T) new String[] {GrimmExtensionTest.class.getName(), String.class.getName(), "com.example.Missing"};
            }

            @Override
            public <T> T get(String key, Class<T> type, T defaultValue) {
                return get(key, type);
            }
        });

        assertTrue(created.classes().contains(GrimmExtensionTest.class));
        assertTrue(created.classes().contains(String.class));
        assertFalse(created.classes().stream().anyMatch(c -> c.getName().equals("com.example.Missing")));
    }
}
