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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesFileConfigSourceTest {

    @Test
    void ordinalIs100() {
        assertEquals(100, new PropertiesFileConfigSource().getOrdinal());
    }

    @Test
    void readsFromTestClasspathVidocqProperties() {
        PropertiesFileConfigSource src = new PropertiesFileConfigSource();
        assertEquals("from-vidocq", src.getValue("vidocq.test.file.key"));
    }

    @Test
    void readsFromApplicationProperties() {
        PropertiesFileConfigSource src = new PropertiesFileConfigSource();
        assertEquals("from-app", src.getValue("vidocq.test.app.key"));
    }

    @Test
    void returnsNullWhenKeyAbsent() {
        assertNull(new PropertiesFileConfigSource().getValue("totally.absent.key"));
    }

    @Test
    void propertyNamesExposesAllKeys() {
        PropertiesFileConfigSource src = new PropertiesFileConfigSource();
        assertTrue(src.getPropertyNames().contains("vidocq.test.file.key"));
        assertTrue(src.getPropertyNames().contains("vidocq.test.app.key"));
    }
}
