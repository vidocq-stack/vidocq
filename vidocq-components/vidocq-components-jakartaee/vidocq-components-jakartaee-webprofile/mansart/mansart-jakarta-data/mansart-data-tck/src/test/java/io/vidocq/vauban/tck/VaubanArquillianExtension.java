package io.vidocq.vauban.tck;

import io.vidocq.mansart.data.tck.MansartTckArchiveAppender;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

public class VaubanArquillianExtension implements LoadableExtension {
    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, VaubanDeployableContainer.class);
        builder.service(TestEnricher.class, VaubanTestEnricher.class);
        // M7-4 — inject the Mansart provider into every TCK deployment so that BCE
        // @Enhancement can scan TCK @Repository interfaces and route them through the
        // runtime Proxy path.
        builder.service(ApplicationArchiveProcessor.class, MansartTckArchiveAppender.class);
    }
}
