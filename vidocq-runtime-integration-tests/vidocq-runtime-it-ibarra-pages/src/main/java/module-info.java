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
module io.vidocq.runtime.it.ibarrapages {
    requires jakarta.servlet;
    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.web.ibarra;
    // The pages vidocq:generate compiles into this module read these APIs.
    requires io.vidocq.ibarra.api;
    requires io.vidocq.peano.api;
    // Read by the generated servlet factory and bean provider.
    requires io.vidocq.foy.api;
    requires io.vidocq.vauban.api;

    // The test boots the runtime without the application layer that `Vidocq.run` creates, so the module
    // declares the servlet factory and the bean provider itself; vidocq:generate adds the page index.
    provides io.vidocq.foy.spi.gen.WebComponent with
            io.vidocq.runtime.it.ibarrapages.GreetServlet$$FoyComponent;
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.it.ibarrapages._VaubanComponents;
}
