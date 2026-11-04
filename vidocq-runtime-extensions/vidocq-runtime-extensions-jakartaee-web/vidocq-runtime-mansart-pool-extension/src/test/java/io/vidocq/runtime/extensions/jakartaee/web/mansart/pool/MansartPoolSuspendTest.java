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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import io.vidocq.runtime.spi.Suspendable;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pools hold JDBC connections a process snapshot cannot carry: closed on suspend, opened again on resume. */
class MansartPoolSuspendTest {

    private final MansartPoolExtension ext = new MansartPoolExtension();

    @AfterEach
    void cleanup() {
        ext.onStop();
    }

    private static String h2(String name) {
        return "jdbc:h2:mem:vid-suspend-" + name + "-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private void start() {
        ext.configure(new TestContexts.MapConfig(Map.of(
                "vidocq.pool.url", h2("default"), "vidocq.pool.username", "sa",
                "vidocq.pool.audit.url", h2("audit"), "vidocq.pool.audit.username", "sa")));
        ext.beforeStart(new VaubanContainerBuilder());
    }

    @Test
    void theExtensionIsSuspendable() {
        assertInstanceOf(Suspendable.class, ext);
    }

    @Test
    void suspendClosesEveryPool_resumeOpensWorkingOnesAgain() throws Exception {
        start();
        var before = ext.pool();
        var auditBefore = NamedDataSourceRegistry.require("audit");
        Suspendable s = ext;

        s.suspend();
        assertThrows(SQLException.class, before::getConnection);
        assertNull(MansartPoolHolder.INSTANCE.get(), "the holder keeps no closed pool");
        assertThrows(RuntimeException.class, () -> NamedDataSourceRegistry.require("audit").getConnection());

        s.resume();
        assertNotNull(ext.pool());
        assertNotSame(before, ext.pool());
        assertTrue(ext.pool() == MansartPoolHolder.INSTANCE.get(), "the holder serves the new pool");
        try (Connection c = ext.pool().getConnection()) {
            assertTrue(c.isValid(1));
        }
        try (Connection c = NamedDataSourceRegistry.require("audit").getConnection()) {
            assertTrue(c.isValid(1));
        }
        assertNotSame(auditBefore, NamedDataSourceRegistry.require("audit"));
    }

    @Test
    void suspendAndResumeAreIdempotent() throws Exception {
        start();
        ext.suspend();
        ext.suspend();
        ext.resume();
        ext.resume();
        try (Connection c = ext.pool().getConnection()) {
            assertTrue(c.isValid(1));
        }
    }
}
