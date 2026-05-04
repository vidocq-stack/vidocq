package io.vidocq.mansart.data.dialect;

import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;

/**
 * Compile-time-resolved model of one entity. Built once at the {@code <clinit>} of the generated
 * {@code _Entity} metamodel class. Immutable, thread-safe, AOT-friendly.
 *
 * <p>{@link #constructor()} is a {@code MethodHandle} for the entity's no-arg constructor obtained
 * via {@code MethodHandles.privateLookupIn(...)} in {@code _Entity.<clinit>}. Used by the row mapper
 * to materialize entity instances without reflection.
 */
public record EntityModel<E>(
        Class<E> entityClass,
        String tableName,
        String schema,
        IdAttribute<E, ?> id,
        Optional<VersionAttribute<E, ?>> version,
        List<Attribute<E, ?>> attributes,
        MethodHandle constructor
) {

    public EntityModel {
        attributes = List.copyOf(attributes);
    }

    public Optional<Attribute<E, ?>> attribute(String name) {
        for (Attribute<E, ?> a : attributes) {
            if (a.name().equals(name)) return Optional.of(a);
        }
        return Optional.empty();
    }
}
