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
 * The JNDI {@code java:} namespace, answered by the running CDI container through Vauban Naming, with the names
 * {@code vidocq.properties} declares. An application that declares names in code requires
 * {@code io.vidocq.vauban.naming} for {@code JndiBindings}.
 */
module io.vidocq.runtime.extensions.jakartaee.web.naming {
    requires transitive io.vidocq.vauban.naming;
    requires io.vidocq.runtime.spi;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.naming.NamingExtension;

    // ConfiguredNames is created in-module by the generated provider: no `opens` to the container.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.extensions.jakartaee.web.naming._VaubanComponents;
}
