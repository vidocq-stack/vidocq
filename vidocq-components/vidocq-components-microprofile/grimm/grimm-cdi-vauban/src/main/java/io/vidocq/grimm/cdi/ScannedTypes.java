package io.vidocq.grimm.cdi;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Holder of the JAX-RS resource / model classes discovered at deployment time
 * by the {@link GrimmExtension} BCE. Injected into {@link GrimmModelCache} so
 * the pipeline can scan them at startup.
 *
 * <p>Plain immutable record — produced by {@link GrimmConfigProducer} from the
 * class names collected during the BCE {@code @Enhancement} phase.</p>
 */
public record ScannedTypes(List<Class<?>> classes) {

    public ScannedTypes {
        Objects.requireNonNull(classes, "classes");
        classes = List.copyOf(classes);
    }

    /** No classes — used when CDI discovery is unavailable. */
    public static ScannedTypes empty() {
        return new ScannedTypes(List.of());
    }

    /** Convenience factory from a collection. */
    public static ScannedTypes of(Collection<Class<?>> classes) {
        return new ScannedTypes(List.copyOf(classes));
    }
}

