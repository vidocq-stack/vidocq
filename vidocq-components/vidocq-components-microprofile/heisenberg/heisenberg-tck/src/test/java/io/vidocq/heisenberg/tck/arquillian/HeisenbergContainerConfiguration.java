/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.heisenberg.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Arquillian configuration for the Heisenberg TCK container — POJO with no required property.
 *
 * <p>Heisenberg is a MicroProfile Fault Tolerance 4.1 implementation:
 * no HTTP resource is required, the TCK is purely CDI in-VM
 * (Arquillian {@code Local} protocol).</p>
 */
public class HeisenbergContainerConfiguration implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        // Nothing to validate.
    }
}

