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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units.VidocqPersistenceUnits;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.api.ClientProxies;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Jakarta Persistence 3.2 on Vidocq, with Noether.
 *
 * <p>{@code noether-jpa-cdi}'s build compatible extension, found by Vauban, turns each persistence unit an injection
 * point names into an {@link EntityManagerFactory} bean, and each persistence context into an {@code EntityManager}
 * one; {@link VidocqPersistenceUnitBootstrap} gives a unit its connections. On start this extension creates every
 * factory, so that a unit that cannot be created fails the start instead of the first request. The container closes
 * the factories when it stops.
 *
 * <p>Before the container boots, it hands Noether the {@code vidocq.persistence.*} keys of the application, as its
 * configuration resolves them: the units they declare, and the data sources and the properties they set on a unit a
 * {@code persistence.xml} declares (Vidocq/vidocq#551).
 *
 * <p>The {@code @Inject} beans of a unit, its {@code @ApplicationScoped} factory and its {@code @TransactionScoped}
 * entity manager, are synthetic beans of normal scopes: {@link ClientProxies} has their client proxies generated
 * with this module, in this package, as {@code noether-jpa-cdi} stays free of Vauban (Vauban BUG-20261113-19).
 *
 * <p>Priority 350: after the pool (200), the transaction manager (250) and Mansart Data (300).
 */
@ClientProxies({EntityManager.class, EntityManagerFactory.class})
public final class NoetherPersistenceExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(NoetherPersistenceExtension.class.getName());
    private static final String KEYS = "vidocq.persistence.";

    @Override
    public String name() {
        return "noether-persistence";
    }

    @Override
    public int priority() {
        return 350;
    }

    @Override
    public void configure(VidocqConfiguration configuration) {
        Map<String, String> keys = new HashMap<>();
        for (String name : configuration.propertyNames()) {
            if (name.startsWith(KEYS)) {
                configuration.property(name).ifPresent(value -> keys.put(name, value));
            }
        }
        VidocqPersistenceUnits.start(keys, new ApplicationModules());
    }

    @Override
    public void onStop() {
        VidocqPersistenceUnits.stop();
    }

    @Override
    public Set<String> configKeys() {
        return Set.of(KEYS + "*");
    }

    @Override
    public void onStart(ExtensionContext context) {
        BeanManager beans = context.beanManager();
        // Each unit once, by name: noether-jpa-cdi gives a unit two factory beans, the injectable one and the one
        // its persistence contexts share, both on the unit's one factory.
        Set<String> units = new TreeSet<>();
        for (Bean<?> bean : beans.getBeans(EntityManagerFactory.class, Any.Literal.INSTANCE)) {
            EntityManagerFactory factory = (EntityManagerFactory) beans.getReference(bean,
                    EntityManagerFactory.class, beans.createCreationalContext(bean));
            units.add(factory.getName());
        }
        if (units.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Noether Persistence: no persistence unit is injected");
        } else {
            LOG.log(System.Logger.Level.INFO, "Noether Persistence: persistence units " + units + " started");
        }
    }
}
