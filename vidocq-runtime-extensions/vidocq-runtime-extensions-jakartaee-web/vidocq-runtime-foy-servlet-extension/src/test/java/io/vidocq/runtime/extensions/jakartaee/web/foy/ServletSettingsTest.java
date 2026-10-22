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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.extensions.essentials.chappe.ChappeListener;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServletSettingsTest {

    @Test
    void defaultsLetFoyPickTheContextPath() {
        ServletSettings s = ServletSettings.from(new MapConfiguration(Map.of()));

        assertNull(s.contextPath());
        assertEquals(ChappeListener.DEFAULT, s.listener());
        assertEquals(30 * 60, s.sessionTimeoutSeconds());
        assertEquals("UTF-8", s.responseCharacterEncoding());
    }

    @Test
    void readsTheFourKeys() {
        ServletSettings s = ServletSettings.from(new MapConfiguration(Map.of(
                "vidocq.servlet.context-path", "/app",
                "vidocq.servlet.listener", "admin",
                "vidocq.servlet.session-timeout", "120",
                "vidocq.servlet.response-character-encoding", " iso-8859-1 ")));

        assertEquals("/app", s.contextPath());
        assertEquals("admin", s.listener());
        assertEquals(120, s.sessionTimeoutSeconds());
        // The canonical name of the charset, whatever the spelling of the configuration.
        assertEquals("ISO-8859-1", s.responseCharacterEncoding());
    }

    @Test
    void badSessionTimeoutNamesTheKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ServletSettings.from(new MapConfiguration(Map.of("vidocq.servlet.session-timeout", "abc"))));
        assertTrue(e.getMessage().contains("vidocq.servlet.session-timeout"), e.getMessage());
    }

    @Test
    void unknownResponseEncodingNamesTheKey() {
        var config = new MapConfiguration(Map.of("vidocq.servlet.response-character-encoding", "no-such-charset"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ServletSettings.from(config));
        assertTrue(e.getMessage().contains("vidocq.servlet.response-character-encoding"), e.getMessage());
        assertTrue(e.getMessage().contains("no-such-charset"), e.getMessage());
    }

    @Test
    void theExtensionDeclaresItsNameAndKeys() {
        FoyExtension extension = new FoyExtension();

        assertEquals("servlet-foy", extension.name());
        assertEquals(600, extension.priority());
        assertEquals(Set.of("vidocq.servlet.context-path", "vidocq.servlet.listener",
                "vidocq.servlet.session-timeout", "vidocq.servlet.response-character-encoding",
                "vidocq.servlet.users.*", "vidocq.servlet.confidential-port",
                "vidocq.servlet.rotate-session-id-on-login"), extension.configKeys());
    }
}
