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
 * A reading notebook: books, their authors and reading notes, kept by Jakarta Persistence 3.2 on Noether and served
 * by Cassini.
 */
module io.vidocq.runtime.examples.noether {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires jakarta.persistence;
    requires jakarta.transaction;

    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
    // Required by cassini-processor APT output: $$CassiniAdapter implements
    // io.vidocq.cassini.spi.gen.ResourceAdapter and uses InjectionSupport/ParamKind.
    requires io.vidocq.cassini.api;
    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    requires io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions;
    // What `vidocq extension add persistence-noether` writes: the entity accessor Noether's processor generates
    // (_NoetherJpaAccess) implements Noether's SPI. It replaces reflection, so the entity package has no `opens`.
    requires io.vidocq.noether.jpa.core;

    // No hand-written `provides` for generated classes: the processors list them in META-INF/services, and the
    // application layer Vidocq creates turns those files into the module's `provides`.
    // Only the `model` package is exported, so Champollion JSON-B reads its records (an export suffices).
    exports io.vidocq.runtime.examples.noether.model;
}
