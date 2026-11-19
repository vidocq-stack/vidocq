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
 * The persistence units of the {@code vidocq.persistence.*} keys, which Noether reads as a
 * {@code PersistenceUnitSource}: Noether knows no Vidocq key (Vidocq/vidocq#551). The Noether Persistence extension
 * hands the source the keys of the running application; at build, {@code vidocq:generate} finds it among the
 * application's dependencies, and it reads them from the application's files.
 */
// "module": the qualified exports name the Noether Persistence extension, built after this module
@SuppressWarnings("module")
module io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units {
    requires io.vidocq.noether.jpa.core;
    requires jakarta.persistence;

    // The extension hands the keys over; nothing else calls this module.
    exports io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units
            to io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

    provides io.vidocq.noether.jpa.core.spi.PersistenceUnitSource
            with io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units.VidocqPersistenceUnits;
}
