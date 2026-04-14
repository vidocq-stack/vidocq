package fr.vidocq.vidocq.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration du container Vidocq embarque pour Arquillian.
 */
public class VidocqContainerConfig implements ContainerConfiguration {

    private String host = "localhost";
    private int port = 0; // 0 = port aleatoire

    @Override
    public void validate() throws ConfigurationException {
        if (port < 0 || port > 65535) {
            throw new ConfigurationException("Port invalide : " + port);
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
