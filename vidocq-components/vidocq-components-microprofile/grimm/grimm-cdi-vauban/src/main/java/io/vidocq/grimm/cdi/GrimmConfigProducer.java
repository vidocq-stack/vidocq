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
package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.config.GrimmConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * CDI producer that exposes the immutable {@link GrimmConfig} snapshot as an injectable
 * bean.
 *
 * <p>Reads the MicroProfile {@link Config} via {@link ConfigProvider#getConfig()} at
 * application-scoped instantiation. In Vidocq deployments this resolves to the Ravel
 * MP-Config provider.</p>
 */
@Dependent
public class GrimmConfigProducer {

    @Produces
    @ApplicationScoped
    public GrimmConfig produceGrimmConfig() {
        try {
            Config config = ConfigProvider.getConfig();
            return GrimmConfig.fromMpConfig(config);
        } catch (IllegalStateException | java.util.ServiceConfigurationError e) {
            // No MP-Config provider on the classpath — fall back to defaults so the
            // pipeline still produces a valid (empty) /openapi document.
            return GrimmConfig.defaults();
        }
    }

    /** Bridges the BCE-discovered {@code @Path} classes into the CDI runtime. */
    @Produces
    @ApplicationScoped
    public ScannedTypes produceScannedTypes() {
        return ScannedTypes.of(GrimmExtension.discoveredTypes());
    }
}


