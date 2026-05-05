package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.core.RuntimeRepositoryProxy;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

/**
 * Synthetic-bean creator for {@code @Repository} interfaces discovered at runtime via the
 * {@code @Enhancement} BCE phase (M7-4) — those that have NO compile-time {@code *Impl} class
 * because {@code mansart-data-processor} did not run on their module.
 *
 * <p>The {@code itfClass} parameter holds the {@code @Repository} interface; this creator
 * resolves the shared {@link RepositoryRuntime} from the container and builds a
 * {@link java.lang.reflect.Proxy}-backed instance via {@link RuntimeRepositoryProxy#create}.
 */
public final class MansartRuntimeRepoCreator implements SyntheticBeanCreator<Object> {

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object create(Instance<Object> lookup, Parameters params) {
        Class<?> itfClass = params.get("itfClass", Class.class);
        if (itfClass == null) {
            throw new MansartDataException("MansartRuntimeRepoCreator: missing 'itfClass' parameter");
        }
        RepositoryRuntime runtime = lookup.select(RepositoryRuntime.class).get();
        return RuntimeRepositoryProxy.create((Class) itfClass, runtime);
    }
}
