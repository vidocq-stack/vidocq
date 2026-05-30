package io.vidocq.humboldt.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * Arquillian entry point — registers {@link HumboldtDeployableContainer}
 * through the {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension} mechanism.
 *
 * <p>When a test starts, Arquillian scans the classpath for
 * {@link LoadableExtension} through {@link java.util.ServiceLoader}, instantiates each
 * extension, and calls {@link #register(ExtensionBuilder)} to collect the
 * services it provides (containers, enrichers, protocols, etc.).</p>
 */
public class HumboldtLoadableExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, HumboldtDeployableContainer.class);
        builder.service(TestEnricher.class, HumboldtCdiEnricher.class);
    }
}
