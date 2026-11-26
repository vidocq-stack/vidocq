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
package io.vidocq.runtime.extensions.essentials.chappe.spi;

/**
 * The default order of the mounts that share a prefix (vidocq#802), the lowest asked first:
 * static files, then the REST resources, then the servlets, then any other handler. A file lookup is
 * cheap and never catches every path, and a servlet application often maps {@code /}. The order of a
 * mount is changed with {@code vidocq.http.mount.<name>.order}, {@code vidocq.rest.order} or
 * {@code vidocq.servlet.order}.
 */
public final class MountOrder {

    /** Static files ({@code type=static}). */
    public static final int STATIC = 100;

    /** REST resources ({@code type=restful} and the automatic mount of the REST extension). */
    public static final int RESTFUL = 200;

    /** The web application ({@code type=servlet} and the automatic mount of the Servlet extension). */
    public static final int SERVLET = 300;

    /** Any other handler: after the others, as it may not be able to pass a request on. */
    public static final int DEFAULT = 1000;

    private MountOrder() {
    }
}
