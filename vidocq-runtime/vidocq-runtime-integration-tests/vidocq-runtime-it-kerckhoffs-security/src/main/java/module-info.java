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
module io.vidocq.runtime.it.kerckhoffs {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.servlet;
    requires jakarta.security;
    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.web.foy;
    requires io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs;
    // Read by the generated factories and adapters below.
    requires io.vidocq.foy.api;
    requires io.vidocq.vauban.api;

    // No exports, no opens. The processors generate in-module code that ServiceLoader finds: the web component
    // factories (foy-processor, through the Servlet codegen bundle) and the instantiation and injection of the beans
    // (Vauban). The test boots the runtime without the application layer that `Vidocq.run` creates, so the module
    // declares the providers itself.
    provides io.vidocq.foy.spi.gen.WebComponent with
            io.vidocq.runtime.it.kerckhoffs.PasswordHashServlet$$FoyComponent,
            io.vidocq.runtime.it.kerckhoffs.PublicServlet$$FoyComponent,
            io.vidocq.runtime.it.kerckhoffs.WhoAmIServlet$$FoyComponent;
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.it.kerckhoffs._VaubanComponents;
}
