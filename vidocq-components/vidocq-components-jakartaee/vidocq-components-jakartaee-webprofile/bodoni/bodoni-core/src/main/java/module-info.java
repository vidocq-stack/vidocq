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
 * <p>The {@code functions} package is exported unqualified; tag-handler
 * packages will be exported unqualified when they land (B1): pages
 * precompiled by Ibarra call {@code new} on handlers from the application
 * module, and other Pages containers instantiate them by class name — neither
 * needs {@code opens}.
 *
 * <p>{@code jakarta.servlet.jsp.jstl} brings {@code jakarta.servlet.jsp},
 * {@code jakarta.servlet}, {@code jakarta.el}, {@code java.sql} and
 * {@code java.desktop} transitively (upstream module descriptor). At the Maven
 * level the Pages, Servlet and EL APIs are {@code provided}: the container
 * supplies them. Bodoni never depends on an EL, Pages or Servlet
 * implementation (see PLAN.md).
 */
module io.vidocq.bodoni.core {
    requires transitive jakarta.servlet.jsp.jstl;

    // The fn: functions are called directly by generated pages.
    exports io.vidocq.bodoni.core.functions;
}
