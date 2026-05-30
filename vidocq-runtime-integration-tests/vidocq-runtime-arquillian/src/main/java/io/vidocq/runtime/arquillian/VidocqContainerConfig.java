package io.vidocq.runtime.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration for the embedded Vidocq Arquillian container.
 */
public class VidocqContainerConfig implements ContainerConfiguration {

    private String host = "localhost";
    private int port = 0; // 0 = random port

    @Override
    public void validate() throws ConfigurationException {
        if (port < 0 || port > 65535) {
            throw new ConfigurationException("Invalid port: " + port);
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
