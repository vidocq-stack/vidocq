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
 * Sécurité JAX-RS de Cervantes pour MicroProfile JWT 2.1.
 *
 * <p>API JAX-RS standard uniquement (filtres, {@code DynamicFeature}, {@code SecurityContext}) :
 * le filtre d'authentification valide le bearer token et pose un {@code SecurityContext} adossé au
 * {@code JsonWebToken} (via {@code setSecurityContext}, pre-matching), et une {@code DynamicFeature}
 * applique {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll} par méthode. Impl-agnostique
 * (validé contre Cassini). Les filtres sont des beans CDI {@code @Provider} découverts par le
 * {@code BeanProvider} de Cassini.</p>
 *
 * <p>Note JPMS — workaround testCompile : {@code module-info.java} dans {@code src/main/module-info/}.
 * Voir {@code cervantes-core/pom.xml}.</p>
 */
module io.vidocq.cervantes.jaxrs {
    requires transitive io.vidocq.cervantes.api;
    requires io.vidocq.cervantes.cdi.vauban;
    requires jakarta.ws.rs;
    requires jakarta.annotation; // @RolesAllowed/@PermitAll/@DenyAll + @Priority lus à l'exécution

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static org.eclipse.microprofile.config; // mp.jwt.token.header / mp.jwt.token.cookie
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    // In-module instantiation of the no-arg @Provider beans (generated as _VaubanComponents),
    // so the container needs no reflection for them.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.cervantes.jaxrs._VaubanComponents;

    exports io.vidocq.cervantes.jaxrs;
    // No `opens … to io.vidocq.vauban.core`: the @Provider beans (auth filter, @RolesAllowed
    // DynamicFeature) are instantiated in-module by the generated _VaubanComponents provider
    // (declared above), so the container needs no deep reflection into this package.
}
