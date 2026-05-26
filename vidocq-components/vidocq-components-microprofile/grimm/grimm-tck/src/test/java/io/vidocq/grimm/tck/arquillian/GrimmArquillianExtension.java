package io.vidocq.grimm.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/** Registers Grimm custom Arquillian container for the OpenAPI TCK. */
public class GrimmArquillianExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, GrimmDeployableContainer.class);
    }
}

