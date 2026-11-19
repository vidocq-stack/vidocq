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
package io.vidocq.runtime.it.noether;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.spi.LoadState;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.ProviderUtil;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Another provider than Noether, as the unit {@code registry} names it (Jakarta Persistence 3.2, §9.1): this module
 * provides it, so the runtime finds it through the {@code PersistenceProviderResolver}. It records the unit the runtime
 * hands it. Its factory answers {@code getName}, {@code isOpen} and {@code close}, and refuses to unwrap, as a factory
 * that is not Noether's does.
 */
public final class RecordingProvider implements PersistenceProvider {

    private static final AtomicReference<PersistenceUnitInfo> LAST = new AtomicReference<>();

    /** The unit the runtime last handed this provider. */
    public static PersistenceUnitInfo last() {
        return LAST.get();
    }

    @Override
    public EntityManagerFactory createContainerEntityManagerFactory(PersistenceUnitInfo unit, Map<?, ?> properties) {
        LAST.set(unit);
        boolean[] open = {true};
        return (EntityManagerFactory) Proxy.newProxyInstance(RecordingProvider.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> unit.getPersistenceUnitName();
                    case "isOpen" -> open[0];
                    case "close" -> {
                        open[0] = false;
                        yield null;
                    }
                    case "unwrap" -> throw new PersistenceException("RecordingProvider unwraps to nothing");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The units of a Java SE bootstrap are Noether's here: this provider declines them all. */
    @Override
    public EntityManagerFactory createEntityManagerFactory(String name, Map<?, ?> properties) {
        return null;
    }

    @Override
    public EntityManagerFactory createEntityManagerFactory(PersistenceConfiguration configuration) {
        return null;
    }

    @Override
    public boolean generateSchema(String name, Map<?, ?> properties) {
        return false;
    }

    @Override
    public void generateSchema(PersistenceUnitInfo unit, Map<?, ?> properties) {
        throw new UnsupportedOperationException("generateSchema");
    }

    @Override
    public ProviderUtil getProviderUtil() {
        return new ProviderUtil() {
            @Override
            public LoadState isLoadedWithoutReference(Object entity, String attribute) {
                return LoadState.UNKNOWN;
            }

            @Override
            public LoadState isLoadedWithReference(Object entity, String attribute) {
                return LoadState.UNKNOWN;
            }

            @Override
            public LoadState isLoaded(Object entity) {
                return LoadState.UNKNOWN;
            }
        };
    }
}
