package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import javax.sql.DataSource;

/**
 * Default CDI producer for {@link RepositoryRuntime}. Backs every {@code @Repository} whose
 * {@code dataStore} attribute is empty (the spec default). Multi-tenant deployments that route
 * every repository through {@code @Repository(dataStore = "name")} never trigger this producer —
 * hence the {@link Instance} indirection: a missing {@code @Default DataSource} is only a
 * problem if some repository actually needs the default runtime.
 *
 * <p>Discovered by {@link MansartDataExtension} via {@code ScannedClasses.add(...)} at
 * {@code @Discovery}. Vauban-processor indexes the class for validation but skips emitting a
 * {@code *_Factory.class} in the user module's output — the class lives in this jar and
 * Vauban-runtime falls back to a reflective factory. Override by declaring your own
 * {@code @Produces RepositoryRuntime} method, which CDI takes in priority.
 */
@Singleton
public class MansartRuntimeProducer {

    @Produces
    @Singleton
    public RepositoryRuntime runtime(Instance<DataSource> defaultDs) {
        if (defaultDs.isUnsatisfied()) {
            throw new MansartDataException(
                    "No @Default DataSource bean found. Either expose one, or route every repository "
                            + "through @Repository(dataStore = \"name\") with a matching @Named DataSource.");
        }
        if (defaultDs.isAmbiguous()) {
            throw new MansartDataException(
                    "Multiple @Default DataSource beans match. Disambiguate with @Named and route "
                            + "each repository via @Repository(dataStore = \"name\").");
        }
        return MansartData.builder().dataSource(defaultDs.get()).build().runtime();
    }
}
