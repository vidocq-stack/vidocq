package io.vidocq.dirac.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Arquillian configuration for the Dirac TCK container — POJO with no required properties.
 */
public class DiracContainerConfiguration implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        // nothing to validate.
    }
}
