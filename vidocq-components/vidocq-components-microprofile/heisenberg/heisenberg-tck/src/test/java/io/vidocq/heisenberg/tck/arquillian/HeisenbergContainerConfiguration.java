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
 * Configuration Arquillian du container Heisenberg TCK — POJO sans propriété requise.
 *
 * <p>Heisenberg est une implémentation MicroProfile Fault Tolerance 4.1 :
 * aucune ressource HTTP n'est nécessaire, le TCK est purement CDI in-VM
 * (protocole Arquillian {@code Local}).</p>
 */
public class HeisenbergContainerConfiguration implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        // rien à valider.
    }
}

