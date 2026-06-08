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
 * CDI integration module for Vauban, providing {@code @Inject @ConfigProperty}
 * support through a Build Compatible Extension.
 *
 * <p>This module is optional: standalone Java SE deployments can use
 * {@code ravel-core} directly through {@code ConfigProvider.getConfig()}.</p>
 */
module io.vidocq.ravel.cdi.vauban {
    requires transitive io.vidocq.ravel.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.ravel.cdi.ConfigCdiExtension;

    provides jakarta.enterprise.inject.spi.Extension
            with io.vidocq.ravel.cdi.ConfigPropertiesExclusionExtension;

    // In-module instantiation and producer invocation of this package's beans (the @Produces Config
    // in RavelConfigProducer), generated as _VaubanComponents co-located in io.vidocq.ravel.cdi — so
    // the container needs no `opens … to io.vidocq.vauban.core`. APT-generated, inert under Weld.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.ravel.cdi._VaubanComponents;

    exports io.vidocq.ravel.cdi;
    exports io.vidocq.ravel.cdi.internal;
}
