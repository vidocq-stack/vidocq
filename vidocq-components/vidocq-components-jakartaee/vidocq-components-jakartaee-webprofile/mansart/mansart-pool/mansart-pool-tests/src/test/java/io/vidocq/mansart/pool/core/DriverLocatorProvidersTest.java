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
package io.vidocq.mansart.pool.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@code DriverLocator} walks the {@code java.sql.Driver} providers of a class loader (mansart#42): a provider
 * that cannot be loaded or cannot judge the URL is skipped, and the first provider that accepts the URL wins.
 * Each case uses a throwaway loader with its own services file, so no global state changes.
 */
class DriverLocatorProvidersTest {

    private static final String URL_PREFIX = "jdbc:mansart-test:";
    private static final String JDBC_URL = URL_PREFIX + "x";

    @TempDir
    Path services;

    @Test
    void aBrokenAndARefusingProviderAreSkippedUntilOneAccepts() throws Exception {
        try (URLClassLoader loader = loaderListing("com.example.NoSuchDriver", name(Refusing.class),
                name(Accepting.class))) {
            assertThat(DriverLocator.find(JDBC_URL, loader)).isInstanceOf(Accepting.class);
        }
    }

    @Test
    void theFirstProviderThatAcceptsTheUrlWins() throws Exception {
        try (URLClassLoader loader = loaderListing(name(AlsoAccepting.class), name(Accepting.class))) {
            assertThat(DriverLocator.find(JDBC_URL, loader)).isInstanceOf(AlsoAccepting.class);
        }
    }

    private URLClassLoader loaderListing(String... providers) throws IOException {
        Path file = services.resolve("META-INF/services/java.sql.Driver");
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(providers));
        return new URLClassLoader(new URL[]{services.toUri().toURL()}, DriverLocatorProvidersTest.class.getClassLoader());
    }

    private static String name(Class<?> type) {
        return type.getName();
    }

    public abstract static class FakeDriver implements Driver {
        @Override
        public Connection connect(String url, Properties info) {
            return null;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    public static class Refusing extends FakeDriver {
        @Override
        public boolean acceptsURL(String url) throws SQLException {
            throw new SQLException("cannot judge " + url);
        }
    }

    public static class Accepting extends FakeDriver {
        @Override
        public boolean acceptsURL(String url) {
            return url.startsWith(URL_PREFIX);
        }
    }

    public static class AlsoAccepting extends FakeDriver {
        @Override
        public boolean acceptsURL(String url) {
            return url.startsWith(URL_PREFIX);
        }
    }
}
