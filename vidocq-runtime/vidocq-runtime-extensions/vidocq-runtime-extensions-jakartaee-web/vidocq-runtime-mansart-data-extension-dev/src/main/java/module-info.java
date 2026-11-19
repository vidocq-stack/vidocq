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
 * The Mansart Data panel of the dev console, its catalogue and the actions that run the repositories' methods, which
 * only vidocq:dev adds (Vidocq/vidocq#143).
 */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev {
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.data;
    requires io.vidocq.runtime.spi.devconsole;
    // The repository beans, resolved at the call.
    requires jakarta.cdi;
    // A write runs in the application's TransactionManager when it has one; without the API, commit only.
    requires static jakarta.transaction;

    provides io.vidocq.runtime.spi.devconsole.LivePanel
            with io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.CatalogueLivePanel;
}
