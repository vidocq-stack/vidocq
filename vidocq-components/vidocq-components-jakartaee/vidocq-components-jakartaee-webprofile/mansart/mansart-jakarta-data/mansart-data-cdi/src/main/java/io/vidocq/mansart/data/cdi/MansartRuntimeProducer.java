package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import javax.sql.DataSource;

/**
 * Default CDI producer for {@link RepositoryRuntime}. Requires the application to expose a
 * {@code DataSource} bean (any scope). Override by declaring your own {@code @Produces
 * RepositoryRuntime} method to take control of the dialect / pool wiring.
 *
 * <p>Uses {@code @Singleton} (rather than {@code @ApplicationScoped}) because
 * {@link RepositoryRuntime} is {@code final} — Singleton avoids the client-proxy requirement.
 */
@Singleton
public class MansartRuntimeProducer {

    @Produces
    @Singleton
    public RepositoryRuntime runtime(DataSource dataSource) {
        return MansartData.builder().dataSource(dataSource).build().runtime();
    }
}
