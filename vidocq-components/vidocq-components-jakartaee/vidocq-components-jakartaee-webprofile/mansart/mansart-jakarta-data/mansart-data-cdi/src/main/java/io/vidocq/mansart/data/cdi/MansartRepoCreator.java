package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.invoke.Invoker;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

/**
 * Synthetic-bean creator used by {@link MansartDataExtension}. Each generated {@code XxxRepository}
 * is exposed as an {@code @ApplicationScoped} synthetic bean whose {@code create} delegates here.
 *
 * <p>The {@code implClass} (a {@link Class}) is passed via {@link Parameters}; we look it up,
 * fetch the shared {@link RepositoryRuntime} from the container, and instantiate via the
 * generated {@code (RepositoryRuntime)} constructor.
 */
public final class MansartRepoCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        Class<?> implClass = params.get("implClass", Class.class);
        if (implClass == null) {
            throw new MansartDataException("MansartRepoCreator: missing 'implClass' parameter");
        }
        RepositoryRuntime runtime = lookup.select(RepositoryRuntime.class).get();
        try {
            var ctor = implClass.getDeclaredConstructor(RepositoryRuntime.class);
            ctor.setAccessible(true);
            return ctor.newInstance(runtime);
        } catch (ReflectiveOperationException e) {
            throw new MansartDataException("Failed to instantiate " + implClass.getName(), e);
        }
    }
}
