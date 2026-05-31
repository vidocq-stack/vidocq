/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.heisenberg.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * Registers {@link HeisenbergDeployableContainer} and {@link HeisenbergTestEnricher}
 * with the Arquillian framework via the {@link LoadableExtension} SPI.
 *
 * <p>Discovered via {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension}.</p>
 */
public class HeisenbergArquillianExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, HeisenbergDeployableContainer.class);
        builder.service(TestEnricher.class, HeisenbergTestEnricher.class);
    }
}

