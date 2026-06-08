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
package io.vidocq.runtime.ext.chappe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChappeServerBootstrapTest {

    @Test
    void defaultListenerUsedWhenNoConfig() {
        List<ChappeListener> listeners = new ChappeServerBootstrap()
                .resolveListeners(TestConfig.of(Map.of()));
        assertEquals(1, listeners.size());
        assertEquals(ChappeListener.DEFAULT, listeners.get(0).name());
        assertEquals("0.0.0.0", listeners.get(0).host());
        assertEquals(8080, listeners.get(0).port());
    }

    @Test
    void explicitDefaultListenerOverridesHostAndPort() {
        var listeners = new ChappeServerBootstrap().resolveListeners(TestConfig.of(Map.of(
                "vidocq.chappe.listener.default.host", "127.0.0.1",
                "vidocq.chappe.listener.default.port", "9000"
        )));
        assertEquals("127.0.0.1", listeners.get(0).host());
        assertEquals(9000, listeners.get(0).port());
    }

    @Test
    void multiListenerResolvedFromCsv() {
        var listeners = new ChappeServerBootstrap().resolveListeners(TestConfig.of(Map.of(
                "vidocq.chappe.listeners", "default,admin",
                "vidocq.chappe.listener.admin.host", "127.0.0.1",
                "vidocq.chappe.listener.admin.port", "9090"
        )));
        assertEquals(2, listeners.size());
        assertEquals("default", listeners.get(0).name());
        assertEquals("admin", listeners.get(1).name());
        assertEquals(9090, listeners.get(1).port());
    }

    @Test
    void nonDefaultListenerWithoutPortThrows() {
        var cfg = TestConfig.of(Map.of("vidocq.chappe.listeners", "admin"));
        var bootstrap = new ChappeServerBootstrap();
        var ex = assertThrows(IllegalStateException.class, () -> bootstrap.resolveListeners(cfg));
        assertTrue(ex.getMessage().contains("admin"));
    }

    @Test
    void blankEntriesInCsvIgnored() {
        var listeners = new ChappeServerBootstrap().resolveListeners(TestConfig.of(Map.of(
                "vidocq.chappe.listeners", "default, ,default",
                "vidocq.chappe.listener.default.port", "8081"
        )));
        assertEquals(2, listeners.size());
    }

    @Test
    void priorityIsTenThousand() {
        assertEquals(10_000, new ChappeServerBootstrap().priority());
    }
}
