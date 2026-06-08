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
package io.vidocq.runtime.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemPropertiesConfigSourceTest {

    private static final String KEY = "vidocq.test.systemprops.key";

    @AfterEach
    void cleanup() {
        System.clearProperty(KEY);
    }

    @Test
    void ordinalIs400() {
        assertEquals(400, new SystemPropertiesConfigSource().getOrdinal());
    }

    @Test
    void readsSystemProperty() {
        System.setProperty(KEY, "hello");
        assertEquals("hello", new SystemPropertiesConfigSource().getValue(KEY));
    }

    @Test
    void returnsNullWhenAbsent() {
        assertNull(new SystemPropertiesConfigSource().getValue("definitely.not.set." + System.nanoTime()));
    }

    @Test
    void propertyNamesIncludesSetKey() {
        System.setProperty(KEY, "v");
        assertTrue(new SystemPropertiesConfigSource().getPropertyNames().contains(KEY));
    }
}
