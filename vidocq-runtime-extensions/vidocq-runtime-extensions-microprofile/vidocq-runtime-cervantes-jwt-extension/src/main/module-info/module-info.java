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
 * Vidocq Runtime wrapper that activates Cervantes (MicroProfile JWT 2.1)
 * via its standard SPIs (BCE CDI 4.1 + @Provider JAX-RS beans).
 *
 * <p>Transitively re-exports Cervantes modules (api, core, cdi.vauban,
 * cassini) so that they are visible on the module-path of a jlink image
 * Vidocq, and republishes the {@code BuildCompatibleExtension} from cervantes-cdi-vauban
 * under the name of this module so that ServiceLoader discovers it.</p>
 */
module io.vidocq.runtime.ext.cervantes.jwt {
    requires transitive io.vidocq.cervantes.api;
    requires transitive io.vidocq.cervantes.core;
    requires transitive io.vidocq.cervantes.cdi.vauban;
    requires transitive io.vidocq.cervantes.jaxrs;

    // MP JWT 2.1 §6.1 — the spec requires reading mp.jwt.verify.* keys via
    // MicroProfile Config. Cervantes-core uses @ConfigProperty for this, so
    // we transitively draw the Ravel extension (which brings ravel-cdi-vauban +
    // the RavelConfigSourceProvider).
    requires transitive io.vidocq.runtime.ext.ravel;

    requires jakarta.cdi;

    // vauban-core instantiates the BCE reflectively (getDeclaredConstructor + setAccessible +
    // newInstance in BceProcessor) — `provides ... with` alone is not enough. The BCE lives in this
    // wrapper's internal, non-exported package, so open it to vauban-core for that reflective access.
    opens io.vidocq.runtime.ext.cervantes to io.vidocq.vauban.core;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension;
}
