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

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Gauge backed by a compile-time generated functional accessor — the
 * no-reflection counterpart of {@link GaugeImpl}, used when a
 * {@code $$DiracMetrics} companion is present (codegen audit CG-05). The
 * accessor calls the gauge method directly, so no
 * {@code MethodHandles.privateLookupIn} (and no {@code opens}) is needed.
 */
public final class FunctionalGaugeImpl<T extends Number> implements Gauge<T> {

    private final Supplier<?> targetSupplier;
    private final Function<Object, Number> invoker;

    /** Static gauge — the accessor is invoked with a {@code null} bean. */
    public FunctionalGaugeImpl(Function<Object, Number> invoker) {
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        this.targetSupplier = null;
    }

    /** Instance gauge — the bean is resolved lazily on every read. */
    public FunctionalGaugeImpl(Supplier<?> targetSupplier, Function<Object, Number> invoker) {
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        this.targetSupplier = Objects.requireNonNull(targetSupplier, "targetSupplier must not be null");
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getValue() {
        try {
            Object target = targetSupplier == null
                    ? null
                    : Objects.requireNonNull(targetSupplier.get(), "target instance must not be null");
            return (T) invoker.apply(target);
        } catch (DiracException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DiracException("Unable to read gauge value", e);
        }
    }
}
