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
package io.vidocq.peano.core.internal.evaluation.layer;

import io.vidocq.peano.spi.ProxyUnwrapper;

/**
 * The proxy unwrapper the simulated application layer brings in its own jar: it knows {@link LayerBeanProxy}, the
 * layer's own copy of it, as a proxy of {@link LayerBean}.
 */
public final class LayerUnwrapper implements ProxyUnwrapper {
    /** Creates the unwrapper, as the service loader does. */
    public LayerUnwrapper() {
        // Public no-argument constructor required by ServiceLoader.
    }

    @Override
    public Class<?> proxiedClass(Class<?> runtime) {
        return runtime == LayerBeanProxy.class ? LayerBean.class : null;
    }
}
