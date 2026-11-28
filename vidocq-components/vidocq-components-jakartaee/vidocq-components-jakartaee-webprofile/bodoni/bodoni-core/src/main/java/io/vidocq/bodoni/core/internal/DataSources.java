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

package io.vidocq.bodoni.core.internal;

import java.sql.SQLException;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * Data-source resolution for the {@code sql:} actions, Tags 3.0 §10.1.1 and §10.2: a
 * {@link DataSource} is used as is; a {@link String} is looked up in JNDI under
 * {@code java:comp/env/} and, when that fails, read as a
 * {@code url[,[driver][,[user][,password]]]} JDBC parameter string. No message built here
 * carries the raw string, which may hold a password.
 */
public final class DataSources {

    /** The JNDI root a string data source is looked up under. */
    public static final String JNDI_ROOT = "java:comp/env/";

    private DataSources() {
    }

    /**
     * Resolves a data source given as an object or a string.
     *
     * @param source a {@link DataSource}, a JNDI path relative to {@code java:comp/env} or a
     *               JDBC parameter string
     * @return the data source
     * @throws SQLException when the source is missing, of another type, or cannot be resolved
     */
    public static DataSource resolve(Object source) throws SQLException {
        if (source instanceof DataSource dataSource) {
            return dataSource;
        }
        if (source instanceof String name) {
            return resolveString(name);
        }
        if (source == null) {
            throw new SQLException("no data source");
        }
        throw new SQLException("a data source must be a javax.sql.DataSource or a String, not a "
                + source.getClass().getName());
    }

    /**
     * Describes a data source for a message, without credentials.
     *
     * @param source the source given by the page
     * @return the redacted string, or a safe description of the object
     */
    public static String describe(Object source) {
        if (source instanceof String text) {
            return JdbcParameters.redact(text);
        }
        if (source instanceof DriverManagerDataSource own) {
            return own.toString();
        }
        return source == null ? "null" : source.getClass().getName();
    }

    private static DataSource resolveString(String name) throws SQLException {
        if (name.isBlank()) {
            throw new SQLException("the data source string is empty");
        }
        Object found = lookup(name);
        if (found == null) {
            // not found, or bound to null: both fall back to the JDBC parameter form
            return new DriverManagerDataSource(JdbcParameters.parse(name));
        }
        if (found instanceof DataSource dataSource) {
            return dataSource;
        }
        throw new SQLException("JNDI name '" + JNDI_ROOT + JdbcParameters.redact(name)
                + "' is bound to a " + found.getClass().getName() + ", not a javax.sql.DataSource");
    }

    /** The bound object, or {@code null} when the name cannot be resolved through JNDI. */
    // The JNDI lookup of a data source name is what Tags 3.0 §10.1.1 mandates (AGENTS.md constraint 7).
    @SuppressWarnings("BanJNDI")
    private static Object lookup(String name) {
        InitialContext context = null;
        try {
            context = new InitialContext();
            return context.lookup(JNDI_ROOT + name);
        } catch (NamingException _) {
            return null;
        } finally {
            close(context);
        }
    }

    private static void close(InitialContext context) {
        if (context != null) {
            try {
                context.close();
            } catch (NamingException _) {
                // nothing to recover: the lookup has already answered
            }
        }
    }
}
