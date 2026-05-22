package io.vidocq.runtime.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/**
 * Extension Arquillian qui enregistre le container Vidocq embarque.
 */
public class VidocqLoadableExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, VidocqEmbeddedContainer.class);
    }
}
