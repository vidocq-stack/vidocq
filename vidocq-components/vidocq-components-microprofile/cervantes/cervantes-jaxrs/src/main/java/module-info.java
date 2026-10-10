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
 * Cervantes JAX-RS security for MicroProfile JWT 2.2.
 *
 * <p>Standard JAX-RS API only (filters, {@code DynamicFeature}, {@code SecurityContext}):
 * the authentication filter validates the bearer token and sets a {@code SecurityContext} backed by
 * the {@code JsonWebToken} (via {@code setSecurityContext}, pre-matching), and a {@code DynamicFeature}
 * applies {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll} per method. Impl-agnostic
 * (validated against Cassini). Filters are CDI {@code @Provider} beans discovered by the
 * Cassini {@code BeanProvider}.</p>
 */
module io.vidocq.cervantes.jaxrs {
    requires transitive io.vidocq.cervantes.api;
    requires io.vidocq.cervantes.cdi.vauban;
    requires jakarta.ws.rs;
    requires jakarta.annotation; // @RolesAllowed/@PermitAll/@DenyAll + @Priority read at runtime

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static org.eclipse.microprofile.config; // mp.jwt.token.header / mp.jwt.token.cookie
    // Required at runtime under any CDI container, not only Vauban: the build weaves a
    // `(io.vidocq.vauban.api.ProxyLink)` entry constructor into the normal-scoped beans, so their
    // classes cannot be loaded without this module. It also supplies the VaubanComponentProvider
    // service type.
    requires io.vidocq.vauban.api;

    // In-module instantiation of the no-arg @Provider beans (generated as _VaubanComponents),
    // so the container needs no reflection for them.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.cervantes.jaxrs._VaubanComponents;

    exports io.vidocq.cervantes.jaxrs;
    // No `opens … to io.vidocq.vauban.core`: the @Provider beans (auth filter, @RolesAllowed
    // DynamicFeature) are instantiated in-module by the generated _VaubanComponents provider
    // (declared above), so the container needs no deep reflection into this package.
}
