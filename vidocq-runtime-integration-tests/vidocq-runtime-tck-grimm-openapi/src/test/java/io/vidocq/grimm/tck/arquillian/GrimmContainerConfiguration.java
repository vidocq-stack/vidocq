/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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


