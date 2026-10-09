/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.persistence;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Set;

/**
 * Connects the Mansart Persistence CDI integration to Vidocq's application lifecycle.
 *
 * <p>The Mansart Build Compatible Extension owns unit bootstrapping and persistence-context
 * lifecycle. This extension verifies descriptor discovery and the corresponding synthetic CDI
 * beans after CDI boot; it does not take ownership of application-provided data sources.</p>
 */
public final class MansartPersistenceIntegrationExtension implements VidocqExtension {
    private static final System.Logger LOG =
            System.getLogger(MansartPersistenceIntegrationExtension.class.getName());

    private volatile List<String> persistenceUnits = List.of();

    @Override
    public String name() {
        return "mansart-persistence";
    }

    @Override
    public int priority() {
        return 350;
    }

    @Override
    public void onStart(ExtensionContext context) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = getClass().getClassLoader();
        persistenceUnits = PersistenceUnitDiscovery.discover(loader);
        if (persistenceUnits.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Mansart Persistence: no persistence units discovered");
            return;
        }

        BeanManager beanManager = context.beanManager();
        Set<Bean<?>> factories = beanManager.getBeans(EntityManagerFactory.class, Any.Literal.INSTANCE);
        Set<Bean<?>> managers = beanManager.getBeans(EntityManager.class, Any.Literal.INSTANCE);
        for (Bean<?> factory : factories) {
            CreationalContext<?> creationalContext = beanManager.createCreationalContext(factory);
            beanManager.getReference(factory, EntityManagerFactory.class, creationalContext);
        }
        LOG.log(System.Logger.Level.INFO,
                "Mansart Persistence: initialized units {0}; CDI exposes {1} factory bean(s) and {2} context bean(s)",
                persistenceUnits, factories.size(), managers.size());
    }

    @Override
    public void onStop() {
        persistenceUnits = List.of();
    }

    List<String> persistenceUnits() {
        return persistenceUnits;
    }
}
