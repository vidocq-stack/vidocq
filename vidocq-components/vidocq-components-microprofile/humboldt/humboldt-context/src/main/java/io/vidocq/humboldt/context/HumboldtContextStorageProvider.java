package io.vidocq.humboldt.context;

import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.ContextStorageProvider;

/**
 * {@link java.util.ServiceLoader} entry point consumed by OpenTelemetry on the
 * first call to {@code Context.current()}.
 *
 * <p>Always returns the singleton instance {@link HumboldtContextStorage#INSTANCE}.</p>
 */
public final class HumboldtContextStorageProvider implements ContextStorageProvider {

    @Override
    public ContextStorage get() {
        return HumboldtContextStorage.INSTANCE;
    }
}
