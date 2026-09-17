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
module io.vidocq.runtime.examples.rest {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;

    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.spi;
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
    // Required by cassini-processor APT output: $$CassiniAdapter implements
    // io.vidocq.cassini.spi.gen.ResourceAdapter and uses InjectionSupport/ParamKind.
    requires io.vidocq.cassini.api;
    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;
    // The external library's beans are served by this application: the module must be
    // in the resolved graph, otherwise its classes are silently unloadable at runtime
    // (/api/external answered 404). Its generated proxies ship inside the extlib jar,
    // enriched at packaging time by the vidocq-runtime-maven-plugin.
    requires io.vidocq.runtime.examples.extlib;

    // ZERO opens AND zero exports on the resource package — and ZERO hand-written
    // `provides` for generated classes: the annotation processors emit standard
    // META-INF/services files ($$CassiniAdapter/$$CassiniRoutes, _VaubanComponents…),
    // and the Vauban application layer promotes them to synthetic `provides` when it
    // resolves this module (VaubanLayerFactory). The application never names a
    // generated class in this file.
    // Only the `model` subpackage is exported, so Champollion JSON-B can read the `Todo`
    // record via publicLookup (an export suffices, no opens) — same pattern as
    // cassini-examples.
    exports io.vidocq.runtime.examples.rest.model;
}
