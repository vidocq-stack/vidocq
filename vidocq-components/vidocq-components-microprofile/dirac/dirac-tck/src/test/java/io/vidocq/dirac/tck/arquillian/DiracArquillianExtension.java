package io.vidocq.dirac.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * Enregistre {@link DiracDeployableContainer} et {@link DiracTestEnricher}
 * auprès du framework Arquillian via le SPI {@link LoadableExtension}.
 *
 * <p>Découverte via {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension}.</p>
 */
public class DiracArquillianExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, DiracDeployableContainer.class);
        builder.service(TestEnricher.class, DiracTestEnricher.class);
    }
}
