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
package io.vidocq.cervantes.cdi;

import io.vidocq.cervantes.cdi.internal.ClaimResolver;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * {@link SyntheticBeanCreator} that resolves, at runtime, the value of a {@code @Claim}
 * injection point by delegating to {@link ClaimResolver}.
 *
 * <p>Top-level {@code public} class with a no-argument constructor (CDI 4.1
 * §SyntheticBeanCreator contract); it is not a bean itself. The current {@link InjectionPoint} and the
 * request's {@link JsonWebTokenContext} are obtained through the {@link Instance} parameter.</p>
 */
public class ClaimSyntheticCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        InjectionPoint injectionPoint = lookup.select(InjectionPoint.class).get();
        JsonWebTokenContext context = lookup.select(JsonWebTokenContext.class).get();
        return ClaimResolver.resolve(injectionPoint, context);
    }
}
