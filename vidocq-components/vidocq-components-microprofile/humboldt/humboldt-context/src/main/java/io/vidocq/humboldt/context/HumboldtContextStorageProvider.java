package io.vidocq.humboldt.context;

import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.ContextStorageProvider;

/**
 * Point d'entrée {@link java.util.ServiceLoader} consommé par OpenTelemetry au
 * premier appel à {@code Context.current()}.
 *
 * <p>Renvoie systématiquement l'instance singleton {@link HumboldtContextStorage#INSTANCE}.</p>
 */
public final class HumboldtContextStorageProvider implements ContextStorageProvider {

    @Override
    public ContextStorage get() {
        return HumboldtContextStorage.INSTANCE;
    }
}
