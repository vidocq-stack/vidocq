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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.mansart.data.dialect.DialectFactory;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database Mansart has no SQL dialect for fails the boot, naming what to add, instead of the first query
 * (BUG-20261030-10); the extension brings the PostgreSQL and H2 dialects itself.
 */
class DialectCheckTest {

    private static DatabaseMetaData product(String name) {
        return (DatabaseMetaData) Proxy.newProxyInstance(DialectCheckTest.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDatabaseProductName" -> name;
                    case "getURL" -> "jdbc:" + name.toLowerCase() + "://db/app";
                    default -> null;
                });
    }

    private static List<DialectFactory> shipped() {
        return ServiceLoader.load(DialectFactory.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    @Test
    void theExtensionBringsThePostgresqlAndH2Dialects() throws Exception {
        assertNull(MansartDataIntegrationExtension.missingDialect(product("PostgreSQL"), shipped()));
        assertNull(MansartDataIntegrationExtension.missingDialect(product("H2"), shipped()));
    }

    @Test
    void aDatabaseWithoutDialectIsNamedWithWhatMansartSupports() throws Exception {
        String message = MansartDataIntegrationExtension.missingDialect(product("MySQL"), shipped());

        assertEquals("Mansart Data: no SQL dialect for the database 'MySQL' (jdbc:mysql://db/app). Mansart Data"
                + " supports H2 and PostgreSQL; another database needs a DialectFactory of its own"
                + " (io.vidocq.mansart.data.dialect).", message);
    }

    @Test
    void withoutAnyDialectTheMessageSaysSo() throws Exception {
        String message = MansartDataIntegrationExtension.missingDialect(product("PostgreSQL"), List.of());

        assertTrue(message.startsWith("Mansart Data: no SQL dialect for the database 'PostgreSQL'"), message);
        assertTrue(message.contains("io.vidocq.mansart:mansart-data-dialect-postgresql"), message);
    }
}
