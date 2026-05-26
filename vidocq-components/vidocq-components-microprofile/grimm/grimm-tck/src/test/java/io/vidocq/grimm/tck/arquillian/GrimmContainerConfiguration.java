package io.vidocq.grimm.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Arquillian container configuration for Grimm TCK embedded runtime.
 */
public class GrimmContainerConfiguration implements ContainerConfiguration {

    private String host = "127.0.0.1";
    private int port = 0;
    private boolean waitForReadiness = true;
    private long readinessTimeoutMillis = 10_000L;
    private String readinessPath = "/openapi";
    private String systemProperties = "";

    @Override
    public void validate() throws ConfigurationException {
        if (host == null || host.isBlank()) {
            throw new ConfigurationException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new ConfigurationException("port must be in [0,65535]");
        }
        if (readinessTimeoutMillis < 100L) {
            throw new ConfigurationException("readinessTimeoutMillis must be >= 100");
        }
        if (readinessPath == null || readinessPath.isBlank() || !readinessPath.startsWith("/")) {
            throw new ConfigurationException("readinessPath must start with '/'");
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

    public boolean isWaitForReadiness() {
        return waitForReadiness;
    }

    public void setWaitForReadiness(boolean waitForReadiness) {
        this.waitForReadiness = waitForReadiness;
    }

    public long getReadinessTimeoutMillis() {
        return readinessTimeoutMillis;
    }

    public void setReadinessTimeoutMillis(long readinessTimeoutMillis) {
        this.readinessTimeoutMillis = readinessTimeoutMillis;
    }

    public String getReadinessPath() {
        return readinessPath;
    }

    public void setReadinessPath(String readinessPath) {
        this.readinessPath = readinessPath;
    }

    public String getSystemProperties() {
        return systemProperties;
    }

    public void setSystemProperties(String systemProperties) {
        this.systemProperties = systemProperties;
    }
}


