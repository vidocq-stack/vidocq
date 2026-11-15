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
package io.vidocq.kerckhoffs.foy;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.inject.Singleton;
import jakarta.security.enterprise.SecurityContext;

/**
 * Registers the {@link SecurityContext} bean of an application served by Foy. It computes from nothing but its own
 * classes, so the build can freeze it. The context is a single object that reads the request of the moment, so it
 * needs no normal scope and no client proxy.
 */
public class FoySecurityContextExtension implements BuildCompatibleExtension {

    @Synthesis
    public void synthesizeSecurityContext(SyntheticComponents components) {
        components.addBean(SecurityContext.class)
                .type(SecurityContext.class)
                .scope(Singleton.class)
                .createWith(SecurityContextCreator.class);
    }
}
