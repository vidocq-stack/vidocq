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
package io.vidocq.runtime.cli.ext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionCoordinateTest {

    @Test
    void parsesExplicitGav() {
        ExtensionCoordinate c = ExtensionCoordinate.parse("com.acme:widget");
        assertEquals("com.acme", c.groupId());
        assertEquals("widget", c.artifactId());
    }

    @Test
    void rejectsMalformedGav() {
        assertThrows(IllegalArgumentException.class, () -> ExtensionCoordinate.parse("no-colon"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionCoordinate.parse("a:b:c"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionCoordinate.parse(":b"));
    }

    @Test
    void rejectsBlankComponents() {
        assertThrows(IllegalArgumentException.class, () -> new ExtensionCoordinate("", "a"));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionCoordinate("g", " "));
    }

    @Test
    void rendersDependencyBlockWithIndent() {
        ExtensionCoordinate c = new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-knock-health-extension");
        String xml = c.dependencyXml("    ");
        String expected = "    <dependency>\n"
                + "        <groupId>io.vidocq.runtime</groupId>\n"
                + "        <artifactId>vidocq-runtime-knock-health-extension</artifactId>\n"
                + "    </dependency>";
        assertEquals(expected, xml);
        assertTrue(xml.startsWith("    <dependency>"));
    }

    @Test
    void toStringIsGav() {
        assertEquals("g:a", new ExtensionCoordinate("g", "a").toString());
    }
}
