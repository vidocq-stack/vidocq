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
package io.vidocq.dirac.internal;

import io.vidocq.dirac.api.DiracException;
import org.eclipse.microprofile.metrics.Gauge;

import java.lang.invoke.MethodHandle;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Gauge implementation based on a {@link MethodHandle} resolved at startup.
 */
public final class GaugeImpl<T extends Number> implements Gauge<T> {
    private final MethodHandle methodHandle;
    private final Supplier<?> targetSupplier;
    private final boolean staticMethod;

    public GaugeImpl(MethodHandle methodHandle) {
        this(methodHandle, null, true);
    }

    public GaugeImpl(Supplier<?> targetSupplier, MethodHandle methodHandle) {
        this(methodHandle, Objects.requireNonNull(targetSupplier, "targetSupplier must not be null"), false);
    }

    private GaugeImpl(MethodHandle methodHandle, Supplier<?> targetSupplier, boolean staticMethod) {
        this.methodHandle = Objects.requireNonNull(methodHandle, "methodHandle must not be null");
        this.targetSupplier = targetSupplier;
        this.staticMethod = staticMethod;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getValue() {
        try {
            var value = staticMethod
                    ? methodHandle.invoke()
                    : methodHandle.invoke(Objects.requireNonNull(targetSupplier.get(), "target instance must not be null"));
            return (T) value;
        } catch (Throwable throwable) {
            throw new DiracException("Unable to read gauge value", throwable);
        }
    }
}
