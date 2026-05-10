/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.knock.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/** Enregistre {@link KnockDeployableContainer} via le SPI Arquillian. */
public class KnockArquillianExtension implements LoadableExtension {
    @Override public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, KnockDeployableContainer.class);
    }
}

