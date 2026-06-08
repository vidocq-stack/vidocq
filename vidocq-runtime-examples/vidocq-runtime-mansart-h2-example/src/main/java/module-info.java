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
 * End-to-end demo: Vidocq + Cassini REST + Mansart Data + Mansart Pool on H2 in-memory.
 */
module io.vidocq.runtime.examples.mansart {
    requires java.logging;
    // APT-generated _Product / Product_ / ProductRepositoryImpl import @Generated.
    // SOURCE-retention, only needed at compile time.
    requires static java.compiler;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires jakarta.persistence;
    requires jakarta.data;
    requires jakarta.transaction;

    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.spi;
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
    // Required by cassini-processor APT output: $$CassiniAdapter implements
    // io.vidocq.cassini.spi.gen.ResourceAdapter and uses InjectionSupport/ParamKind.
    requires io.vidocq.cassini.api;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.data;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions;
    // MicroProfile Config (opt-in) — provides the RavelConfigSourceProvider which
    // replaces native Vidocq ConfigSources with MP sources at boot, and
    // activates @ConfigProperty via the transitive BCE ravel-cdi-vauban.
    requires io.vidocq.runtime.extensions.microprofile.ravel;

    // OpenAPI document (/openapi) + Swagger UI (/openapi/ui).
    requires io.vidocq.runtime.extensions.microprofile.grimm.openapi;
    requires io.vidocq.runtime.extensions.microprofile.grimm.openapi.ui;

    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    requires io.vidocq.mansart.data.core;

    // JAX-RS reflects on resource classes; JSON-B reflects on the Product record.
    opens io.vidocq.runtime.examples.mansart;
}
