package io.vidocq.cervantes.cdi.internal;

import org.eclipse.microprofile.jwt.ClaimValue;

import java.util.function.Supplier;

/**
 * Implémentation paresseuse de {@link ClaimValue} : {@link #getValue()} relit le claim depuis le
 * token de la requête courante à chaque appel (spec MicroProfile JWT 2.1 — un {@code ClaimValue}
 * injecté dans un bean {@code @ApplicationScoped} doit refléter la requête active).
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
