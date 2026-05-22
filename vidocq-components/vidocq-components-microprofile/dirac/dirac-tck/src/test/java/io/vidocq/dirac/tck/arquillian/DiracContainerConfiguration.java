package io.vidocq.dirac.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration Arquillian du container Dirac TCK — POJO sans propriété requise.
 */
public class DiracContainerConfiguration implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        // rien à valider.
    }
}
