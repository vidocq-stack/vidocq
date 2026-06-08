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
package io.vidocq.runtime.spi;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * Context provided to extensions during the {@link VidocqExtension#onStart} phase.
 *
 * <p>Provides access to the CDI container and configuration.</p>
 */
public interface ExtensionContext {

    /**
     * The initialized Vauban CDI container.
     */
    VaubanContainer container();

    /**
     * The legacy Vidocq configuration.
     *
     * <p>Prefer {@link #config()} for the modern typed API.</p>
     */
    VidocqConfiguration configuration();

    /**
     * The modern Vidocq configuration.
     *
     * <p>Uses typed sources and aligns with MicroProfile Config concepts.</p>
     */
    VidocqConfig config();

    /**
     * Shortcut to the CDI {@link BeanManager}.
     */
    default BeanManager beanManager() {
        return container().getBeanManager();
    }
}
