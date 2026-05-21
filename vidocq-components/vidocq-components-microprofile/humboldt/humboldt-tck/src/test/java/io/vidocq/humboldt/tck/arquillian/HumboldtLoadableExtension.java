package io.vidocq.humboldt.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * Point d'entrée Arquillian — enregistre {@link HumboldtDeployableContainer}
 * via le mécanisme {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension}.
 *
 * <p>Au démarrage d'un test, Arquillian scanne le classpath pour
 * {@link LoadableExtension} via {@link java.util.ServiceLoader}, instancie chaque
 * extension et appelle {@link #register(ExtensionBuilder)} pour collecter les
 * services qu'elle fournit (containers, enrichers, protocols, etc.).</p>
 */
public class HumboldtLoadableExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, HumboldtDeployableContainer.class);
        builder.service(TestEnricher.class, HumboldtCdiEnricher.class);
    }
}
