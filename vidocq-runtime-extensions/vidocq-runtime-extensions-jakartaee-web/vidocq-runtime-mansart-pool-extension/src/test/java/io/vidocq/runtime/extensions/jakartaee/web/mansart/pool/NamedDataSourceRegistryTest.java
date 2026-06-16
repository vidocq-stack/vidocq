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

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamedDataSourceRegistryTest {

    private static final DataSource FAKE = new FakeDataSource();

    @Test
    void registerThenRequireReturnsSameInstance() {
        NamedDataSourceRegistry.register("analytics", FAKE);
        try {
            assertSame(FAKE, NamedDataSourceRegistry.require("analytics"));
        } finally {
            NamedDataSourceRegistry.unregister("analytics");
        }
    }

    @Test
    void requireUnknownThrowsWithHelpfulMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NamedDataSourceRegistry.require("missing"));
        assertTrue(ex.getMessage().contains("missing"), ex.getMessage());
        assertTrue(ex.getMessage().contains("vidocq.pool.missing.url"), ex.getMessage());
    }

    @Test
    void unregisterRemovesMapping() {
        NamedDataSourceRegistry.register("tmp", FAKE);
        NamedDataSourceRegistry.unregister("tmp");
        assertThrows(IllegalStateException.class, () -> NamedDataSourceRegistry.require("tmp"));
    }

    @Test
    void generatedHolderDelegatesToRegistry() throws Exception {
        NamedDataSourceRegistry.register("h", FAKE);
        try {
            // a stand-in for the generated `_h$DataSource extends AbstractNamedDataSourceHolder`
            AbstractNamedDataSourceHolder holder = new AbstractNamedDataSourceHolder("h") {
            };
            assertEquals("h", holder.dataSourceName());
            assertEquals(42, holder.getLoginTimeout()); // forwarded to FAKE
        } finally {
            NamedDataSourceRegistry.unregister("h");
        }
    }

    /** Minimal DataSource whose getLoginTimeout returns a sentinel, to prove delegation. */
    private static final class FakeDataSource implements DataSource {
        @Override public Connection getConnection()                          { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String u, String p)        { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter()                          { return null; }
        @Override public void setLogWriter(PrintWriter out)                  { }
        @Override public void setLoginTimeout(int seconds)                   { }
        @Override public int getLoginTimeout()                               { return 42; }
        @Override public Logger getParentLogger()                            { return null; }
        @Override public <T> T unwrap(Class<T> iface)                        { return null; }
        @Override public boolean isWrapperFor(Class<?> iface)                { return false; }
    }
}
