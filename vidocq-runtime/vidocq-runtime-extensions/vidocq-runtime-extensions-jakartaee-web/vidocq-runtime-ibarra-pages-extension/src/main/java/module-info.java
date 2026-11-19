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
 * Jakarta Pages through Ibarra: Foy serves the JSP pages that {@code vidocq:generate} compiled into the
 * application module. The application module requires {@code io.vidocq.ibarra.api}.
 */
module io.vidocq.runtime.extensions.jakartaee.web.ibarra {
    requires transitive io.vidocq.ibarra.api;
    requires io.vidocq.ibarra.foy;
    requires io.vidocq.runtime.spi;
    requires jakarta.cdi;
    requires io.vidocq.foy.api;
    requires jakarta.el;
    // The Servlet extension serves the pages; Peano evaluates their expressions.
    requires io.vidocq.runtime.extensions.jakartaee.web.foy;
    requires io.vidocq.runtime.extensions.jakartaee.core.peano;

    uses io.vidocq.ibarra.spi.PageIndex;

    // CDI's bean names in the pages' EL context, registered once the application is deployed.
    provides io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletDeploymentListener
            with io.vidocq.runtime.extensions.jakartaee.web.ibarra.CdiPageNames;

    // The @Dependent beans a page reads by name are destroyed when its request ends.
    provides io.vidocq.foy.spi.cdi.CdiContextListeners
            with io.vidocq.runtime.extensions.jakartaee.web.ibarra.RequestDependents;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.ibarra.IbarraPagesExtension;
}
