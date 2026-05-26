package io.vidocq.grimm.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Arquillian container configuration for Grimm TCK embedded runtime.
 */
public class GrimmContainerConfiguration implements ContainerConfiguration {

    private String host = "127.0.0.1";
    private int port = 0;

    @Override
    public void validate() throws ConfigurationException {
        if (host == null || host.isBlank()) {
            throw new ConfigurationException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new ConfigurationException("port must be in [0,65535]");
        }
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}

