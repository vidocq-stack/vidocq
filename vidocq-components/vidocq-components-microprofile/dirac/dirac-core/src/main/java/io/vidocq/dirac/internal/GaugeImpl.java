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
