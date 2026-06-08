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
package io.vidocq.runtime.extensions.microprofile.cervantes;

import io.vidocq.cervantes.cdi.CervantesClaimExtension;

/**
 * BCE relay local to wrapper module to republish CDI Cervantes extension
 * (MicroProfile JWT 2.1) via ServiceLoader and provides JPMS, in the same way
 * that {@code vidocq-runtime-cyrano-rest-client-extension} republishes ECB Cyrano.
 *
 * <p>The ECB {@link CervantesClaimExtension} (cervantes-cdi-vauban) adds the
 * producer {@code @RequestScoped JsonWebToken} and injection {@code @Claim}.
 * JAX-RS security beans (authentication filter + DynamicFeature
 * {@code @RolesAllowed}) of cervantes-jaxrs are {@code @Provider}
 * discovered by Cassini's {@code VaubanBeanProvider} once Cervantes-Cassini
 * present on the deployment classpath.</p>
 */
public final class CervantesJwtBuildCompatibleExtension extends CervantesClaimExtension {
}
