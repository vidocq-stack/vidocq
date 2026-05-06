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
 * every repository through {@code @Repository(dataStore = "name")} never trigger this producer
 * — hence the {@link Instance} indirection: a missing {@code @Default DataSource} is only a
 * problem if some repository actually needs the default runtime.
 *
 * <p>Override by declaring your own {@code @Produces RepositoryRuntime} method to take control
 * of the dialect / pool wiring. Uses {@code @Singleton} (rather than {@code @ApplicationScoped})
 * because {@link RepositoryRuntime} is {@code final} — Singleton avoids the client-proxy requirement.
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
