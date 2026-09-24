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
 * Vidocq/vidocq#123 end-to-end vehicle: no beans of its own, just the {@code @VidocqMain} trampoline
 * {@code vidocq:run} and {@code vidocq:dev} launch. The one PostgreSQL datasource this module's tests use
 * is reached with {@code java.sql} directly, never through a Mansart pool, so no {@code opens} is needed.
 */
module io.vidocq.runtime.it.devservices {
    requires io.vidocq.runtime.core;
    // DevServicesTestHostIT (patched into this module by Surefire) queries the injected datasource with
    // java.sql directly.
    requires static java.sql;

    exports io.vidocq.runtime.it.devservices;
}
