package io.vidocq.cervantes.cdi.internal;

import org.eclipse.microprofile.jwt.ClaimValue;

import java.util.function.Supplier;

/**
 * Lazy implementation of {@link ClaimValue}: {@link #getValue()} rereads the claim from the
 * token of the current query at each call (spec MicroProfile JWT 2.1 — a {@code ClaimValue}
 * injected into a {@code @ApplicationScoped} bean should reflect the active request).
 *
 * @param <T> type de la valeur du claim
 */
public final class ClaimValueImpl<T> implements ClaimValue<T> {

    private final String name;
    private final Supplier<T> value;

    @SuppressWarnings("unchecked")
    ClaimValueImpl(String name, Supplier<?> value) {
        this.name = name;
        this.value = (Supplier<T>) value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public T getValue() {
        return value.get();
    }

    @Override
    public String toString() {
        return "ClaimValue[" + name + "=" + value.get() + "]";
    }
}
