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
package io.vidocq.runtime.extensions.jakartaee.core.peano;

import io.vidocq.peano.spi.ProxyUnwrapper;
import io.vidocq.vauban.api.ClientProxyClass;

/**
 * Names the bean class a Vauban client proxy stands for, as {@link ClientProxyClass} reads it: Vauban generates
 * {@code <Bean>_ClientProxy}, which delegates every call to the contextual instance. With it, the accessor table Peano
 * generated for a normal-scoped {@code @Named} bean serves the proxy EL receives, so a page reads the bean without
 * reflection. That table casts the proxy to the class named, so the unwrapper names it only when the proxy extends it,
 * in its package: past a final method of the bean class, or for a final bean class, the proxy extends a class above
 * it, and EL reads it by reflection (BUG-20261118-02).
 */
public final class VaubanProxyUnwrapper implements ProxyUnwrapper {

    /** Creates the unwrapper; ServiceLoader calls it. */
    public VaubanProxyUnwrapper() {
        // Stateless.
    }

    @Override
    public Class<?> proxiedClass(Class<?> runtime) {
        return ClientProxyClass.beanClassOf(runtime)
                .filter(bean -> bean.isAssignableFrom(runtime)
                        && bean.getPackageName().equals(runtime.getPackageName()))
                .orElse(null);
    }
}
