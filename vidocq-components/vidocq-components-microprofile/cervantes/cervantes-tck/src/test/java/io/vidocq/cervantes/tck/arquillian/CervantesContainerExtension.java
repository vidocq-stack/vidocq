package io.vidocq.cervantes.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/** Enregistre {@link CervantesJwtDeployableContainer} via le SPI Arquillian. */
public class CervantesContainerExtension implements LoadableExtension {
    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, CervantesJwtDeployableContainer.class);
    }
}
