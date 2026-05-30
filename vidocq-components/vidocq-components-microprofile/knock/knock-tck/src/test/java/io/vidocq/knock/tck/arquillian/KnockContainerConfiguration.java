/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.knock.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/** Arquillian configuration for the Knock-Cassini container (default host 127.0.0.1). */
public class KnockContainerConfiguration implements ContainerConfiguration {

    private String host = "127.0.0.1";

    @Override public void validate() throws ConfigurationException {
        if (host == null || host.isBlank()) {
            throw new ConfigurationException("host must not be blank");
        }
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
}

