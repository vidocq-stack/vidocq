package io.vidocq.dirac.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * Registers {@link DiracDeployableContainer} and {@link DiracTestEnricher}
 * with the Arquillian framework via the {@link LoadableExtension} SPI.
 *
 * <p>Discovery via {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension}.</p>
 */
public class DiracArquillianExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, DiracDeployableContainer.class);
        builder.service(TestEnricher.class, DiracTestEnricher.class);
    }
}
