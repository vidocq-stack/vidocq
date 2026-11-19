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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationDriversTest {

    @Test
    void connectsThroughADriverTheLoaderSees() throws Exception {
        try (var connection = ApplicationDrivers.connect("jdbc:h2:mem:drivers", "sa", "",
                ApplicationDriversTest.class.getClassLoader())) {
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void noDriverNamesTheUrlAndTheFix() {
        var e = assertThrows(SQLException.class, () -> ApplicationDrivers.connect("jdbc:nothing:here", "u", "p",
                ApplicationDriversTest.class.getClassLoader()));
        assertTrue(e.getMessage().contains("jdbc:nothing:here"), e.getMessage());
        assertTrue(e.getMessage().contains("add the JDBC driver as a dependency"), e.getMessage());
    }

    /** A broken provider before H2 does not stop the scan: H2 still connects. */
    @Test
    void aBrokenProviderDoesNotHideTheNextOne(@TempDir Path dir) throws Exception {
        ClassLoader loader = driversListedAs(dir, "com.example.MissingDriver", "org.h2.Driver");

        try (var connection = ApplicationDrivers.connect("jdbc:h2:mem:broken-first", "sa", "", loader)) {
            assertFalse(connection.isClosed());
        }
    }

    /** With no driver for the URL, the error names the URL and the fix and carries the broken providers. */
    @Test
    void noDriverCarriesTheBrokenProviders(@TempDir Path dir) throws Exception {
        ClassLoader loader = driversListedAs(dir, "com.example.MissingDriver", "org.h2.Driver");

        var e = assertThrows(SQLException.class, () -> ApplicationDrivers.connect("jdbc:nothing:here", "u", "p",
                loader));
        assertTrue(e.getMessage().contains("jdbc:nothing:here"), e.getMessage());
        assertTrue(e.getMessage().contains("add the JDBC driver as a dependency"), e.getMessage());
        assertEquals(1, e.getSuppressed().length, () -> List.of(e.getSuppressed()).toString());
        assertInstanceOf(ServiceConfigurationError.class, e.getSuppressed()[0]);
        assertTrue(e.getSuppressed()[0].getMessage().contains("com.example.MissingDriver"),
                e.getSuppressed()[0].getMessage());
    }

    /**
     * A loader that sees the test's classes but lists exactly {@code drivers}, in this order, as the
     * {@code java.sql.Driver} providers on its class path.
     */
    private static ClassLoader driversListedAs(Path dir, String... drivers) throws Exception {
        Path services = dir.resolve("META-INF/services/java.sql.Driver");
        Files.createDirectories(services.getParent());
        Files.writeString(services, String.join("\n", drivers) + "\n");
        URL listing = services.toUri().toURL();
        return new ClassLoader("drivers-listed", ApplicationDriversTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return "META-INF/services/java.sql.Driver".equals(name)
                        ? Collections.enumeration(List.of(listing))
                        : super.getResources(name);
            }
        };
    }
}
