package io.vidocq.humboldt.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration for the Humboldt Arquillian container — currently no parameters
 * exposed yet (M7b.4b.1: skeleton). Options are planned for later
 * steps: HTTP port (Chappe), classpath isolation, etc.
 */
public final class HumboldtContainerConfig implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        // M7b.4b.1: no options to validate. Method kept for later
        // steps (M7b.4b.2+) that will add HTTP port, isolation, etc.
    }
}
