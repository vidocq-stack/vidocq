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
module io.vidocq.runtime.examples.servlet {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.servlet;

    requires io.vidocq.runtime.extensions.jakartaee.web.foy;
    // JSP pages: vidocq:generate compiles them into this module, which reads what they use.
    requires io.vidocq.runtime.extensions.jakartaee.web.ibarra;
    requires io.vidocq.ibarra.api;
    requires io.vidocq.peano.api;
    // Read by the code the Servlet codegen bundle generates: the $$FoyComponent factories.
    requires io.vidocq.foy.api;
    requires io.vidocq.vauban.api;

    // No exports, no opens, no hand-written provides: the processors write
    // META-INF/services files and the Vauban application layer promotes them to
    // provides when `Vidocq.run` resolves this module.
}
