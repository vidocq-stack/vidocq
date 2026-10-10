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
 * Module REST optionnel de Dirac exposant l'endpoint MicroProfile Metrics §2.3.
 */
module io.vidocq.dirac.rest {
    requires io.vidocq.dirac.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.ws.rs;
    // Required at runtime under any CDI container, not only Vauban: the build weaves a
    // `(io.vidocq.vauban.api.ProxyLink)` entry constructor into the normal-scoped beans, so their
    // classes cannot be loaded without this module. It also supplies the VaubanComponentProvider
    // service type.
    requires io.vidocq.vauban.api;
    // Compile-only (optional at runtime): the generated MetricsResource$$CassiniAdapter implements a
    // cassini-api type. `requires static` keeps dirac-rest runtime-agnostic — the pre-generated
    // adapter stays dormant unless a Cassini runtime is present.
    requires static io.vidocq.cassini.api;

    exports io.vidocq.dirac.rest;

    // In-module instantiation and field injection of MetricsResource (its package-private @Inject
    // MetricRegistry fields are assigned by an in-package putfield) through the generated
    // _VaubanComponents — so Vauban needs no `opens … to io.vidocq.vauban.core`.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.dirac.rest._VaubanComponents;

    // Still opened to the CDI/JAX-RS runtime for its own introspection (not to Vauban).
    opens io.vidocq.dirac.rest to jakarta.cdi, jakarta.ws.rs;
}

