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
/**
 * Vidocq Runtime extension for Mansart Jakarta Persistence integration.
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.persistence;

import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.ExtensionContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Set;
import java.lang.System;

/**
 * Vidocq Runtime extension that provides integration with Mansart Jakarta Persistence.
 * This extension:
 * - Validates JPA configuration at startup
 * - Logs discovered entities
 * - Provides integration hooks for persistence unit management
 */
public final class MansartPersistenceIntegrationExtension implements VidocqExtension {

    private static final java.util.logging.Logger LOG = 
            java.util.logging.Logger.getLogger(MansartPersistenceIntegrationExtension.class.getName());

    /**
     * Default constructor.
     */
    public MansartPersistenceIntegrationExtension() {
    }

    /**
     * Returns the name of this extension.
     *
     * @return the extension name
     */
    @Override
    public String name() {
        return "mansart-persistence";
    }

    /**
     * Returns the priority of this extension.
     *
     * @return the priority (350 - after mansart-data at 300, before Cassini at 500)
     */
    @Override
    public int priority() {
        return 350;
    }

    /**
     * Called when the extension is started.
     *
     * @param context the extension context
     */
    @Override
    public void onStart(ExtensionContext context) {
        BeanManager bm = context.beanManager();

        if (bm == null) {
            LOG.warning(
                "Mansart Persistence: No BeanManager available in extension context");
            return;
        }

        // Check for EntityManager beans
        Set<Bean<?>> emBeans = bm.getBeans(EntityManager.class);
        if (emBeans == null || emBeans.isEmpty()) {
            LOG.warning(
                "Mansart Persistence: No EntityManager bean found. " +
                "Ensure mansart-persistence-cdi is deployed and persistence.xml is configured.");
        } else {
            LOG.info(
                "Mansart Persistence: EntityManager beans discovered - count: " + emBeans.size());
        }

        // Check for EntityManagerFactory beans
        Set<Bean<?>> emfBeans = bm.getBeans(EntityManagerFactory.class);
        if (emfBeans == null || emfBeans.isEmpty()) {
            LOG.info(
                "Mansart Persistence: No EntityManagerFactory beans found");
        } else {
            LOG.info(
                "Mansart Persistence: EntityManagerFactory beans discovered - count: " + emfBeans.size());
        }

        // Validate persistence configuration
        validatePersistenceConfiguration(context);

        // Log entity inventory
        logEntityInventory(bm);
    }

    /**
     * Validates the persistence configuration.
     *
     * @param context the extension context
     */
    private void validatePersistenceConfiguration(ExtensionContext context) {
        // TODO: Implement persistence configuration validation
        // This will check:
        // - Required properties are present
        // - Database connectivity is available
        // - Dialect configuration is valid
        LOG.info("Mansart Persistence: Persistence configuration validation not yet implemented");
    }

    /**
     * Logs the inventory of discovered JPA entities.
     *
     * @param bm the BeanManager
     */
    private void logEntityInventory(BeanManager bm) {
        // TODO: Implement entity inventory logging
        // This will scan for @Entity classes and log them
        LOG.info("Mansart Persistence: Entity inventory logging not yet implemented");
    }

    /**
     * Shutdown phase: release resources.
     */
    @Override
    public void onStop() {
        LOG.info("Mansart Persistence: Extension stopped");
    }
}
