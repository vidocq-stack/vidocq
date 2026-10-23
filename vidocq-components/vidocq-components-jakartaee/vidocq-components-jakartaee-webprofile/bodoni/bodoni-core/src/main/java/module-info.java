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

/**
 * Bodoni — Jakarta Standard Tag Library 3.0 implementation.
 *
 * <p>The {@code functions} package and the tag-handler packages
 * ({@code tags.core}, {@code tags.fmt} and {@code tags.xml}; {@code sql} to come) are
 * exported unqualified: pages precompiled by Ibarra call {@code new} on
 * handlers from the application module, and other Pages containers
 * instantiate them by class name — neither needs {@code opens}. The
 * {@code internal} package is not exported.
 *
 * <p>{@code jakarta.servlet.jsp.jstl} brings {@code jakarta.servlet.jsp},
 * {@code jakarta.servlet}, {@code jakarta.el}, {@code java.sql} and
 * {@code java.desktop} transitively (upstream module descriptor). Bodoni
 * {@code requires transitive java.xml} itself: the {@code x:} handlers use it
 * directly and their exported signatures expose {@code org.xml.sax} and
 * {@code javax.xml.transform} types, so it does not rely on the API module
 * reaching {@code java.xml} through {@code java.desktop}. For the same reason
 * it {@code requires transitive java.sql}: pages hand {@code javax.sql.DataSource}
 * objects to the {@code sql:} actions and read {@code java.sql} values from
 * {@code Result} rows. It {@code uses java.sql.Driver}: the JDBC driver of a
 * {@code url[,[driver][,[user][,password]]]} data source is found through the
 * {@code ServiceLoader} of the thread context class loader, since
 * {@code DriverManager} refuses a driver its caller's class loader cannot see
 * (a driver in a WAR, Bodoni in the server's lib). At the Maven
 * level the Pages, Servlet and EL APIs are {@code provided}: the container
 * supplies them. Bodoni never depends on an EL, Pages or Servlet
 * implementation (see PLAN.md).
 */
module io.vidocq.bodoni.core {
    requires transitive jakarta.servlet.jsp.jstl;

    // Exported handler signatures expose org.xml.sax and javax.xml.transform types; Bodoni
    // does not rely on the API module's java.desktop chain to reach java.xml.
    requires transitive java.xml;

    // Pages hand javax.sql.DataSource objects to the sql: actions and read java.sql values from
    // Result rows; Bodoni does not rely on the API module's descriptor for java.sql.
    requires transitive java.sql;

    // JDBC drivers of the application are found through the thread context class loader.
    uses java.sql.Driver;

    // String data sources of the sql: actions are looked up under java:comp/env; no exported
    // signature exposes a javax.naming type.
    requires java.naming;

    // c:import reads absolute http(s) URLs through java.net.http.
    requires java.net.http;

    // The fn: functions are called directly by generated pages.
    exports io.vidocq.bodoni.core.functions;

    // Tag handlers are instantiated by name by Pages containers and by new in precompiled pages.
    exports io.vidocq.bodoni.core.tags.core;
    exports io.vidocq.bodoni.core.tags.fmt;
    exports io.vidocq.bodoni.core.tags.xml;
}
