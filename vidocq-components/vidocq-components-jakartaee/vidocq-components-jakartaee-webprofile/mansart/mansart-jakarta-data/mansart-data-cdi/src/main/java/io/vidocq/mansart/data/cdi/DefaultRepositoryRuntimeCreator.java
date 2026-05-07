package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

import javax.sql.DataSource;

/**
 * Synthetic-bean creator that supplies the default {@link RepositoryRuntime} when no user-defined
 * {@code @Produces RepositoryRuntime} method is present in the bean archive.
 *
 * <p>Wired by {@link MansartDataExtension} via {@code @Synthesis components.addBean(...)}. The
 * synthetic-bean route is preferred over a real {@code @Produces} class shipped with
 * {@code mansart-data-cdi.jar} for two reasons:
 *
 * <ol>
 *   <li><b>JPMS friendliness</b> — a real producer class would force compile-time bean processors
 *       (Vauban) running on the user module to generate a {@code *_Factory.class} in the producer's
 *       package, creating a split-package between the user module and {@code mansart-data-cdi}.</li>
 *   <li><b>Simpler scan model</b> — the synthetic bean lives only in the container's bean index,
 *       it never has to be reachable through the APT classloader nor scanned via
 *       {@link jakarta.enterprise.inject.build.compatible.spi.ScannedClasses}, which has surprising
 *       failure modes between Maven and IDE incremental builds.</li>
 * </ol>
 *
 * <p>An application can still override by declaring its own {@code @Produces RepositoryRuntime}
 * method with a more specific qualifier or via a custom {@code @Repository(dataStore = "name")}.
 */
public final class DefaultRepositoryRuntimeCreator implements SyntheticBeanCreator<RepositoryRuntime> {

    @Override
    public RepositoryRuntime create(Instance<Object> lookup, Parameters params) {
        Instance<DataSource> ds = lookup.select(DataSource.class);
        if (ds.isUnsatisfied()) {
            throw new MansartDataException(
                    "No @Default DataSource bean found. Either expose one, or route every repository "
                            + "through @Repository(dataStore = \"name\") with a matching @Named DataSource.");
        }
        if (ds.isAmbiguous()) {
            throw new MansartDataException(
                    "Multiple @Default DataSource beans match. Disambiguate with @Named and route "
                            + "each repository via @Repository(dataStore = \"name\").");
        }
        return MansartData.builder().dataSource(ds.get()).build().runtime();
    }
}
