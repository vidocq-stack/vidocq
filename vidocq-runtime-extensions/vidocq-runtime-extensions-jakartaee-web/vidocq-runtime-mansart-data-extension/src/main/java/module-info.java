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
 * Vidocq extension that fails fast on boot if Mansart Data cannot reach its {@link
 * javax.sql.DataSource}, logs the {@code @Repository} interfaces wired by the underlying
 * {@code mansart-data-cdi} BCE, and writes their catalogue — entities, columns, repositories and methods — as the
 * {@code mansart-data} section of the startup report.
 *
 * <p>The actual repository discovery is done by Vauban's automatic scan of
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * — this extension does not register the BCE itself; it only validates wiring at boot.
 */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.data {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    requires transitive io.vidocq.mansart.data.core;
    requires transitive io.vidocq.mansart.data.cdi;
    requires transitive jakarta.data;
    requires jakarta.cdi;
    requires java.sql;        // javax.sql.DataSource

    // What the catalogue panel of the -dev module reads; no other module sees it.
    exports io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live
            to io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.mansart.data.MansartDataIntegrationExtension;
}
