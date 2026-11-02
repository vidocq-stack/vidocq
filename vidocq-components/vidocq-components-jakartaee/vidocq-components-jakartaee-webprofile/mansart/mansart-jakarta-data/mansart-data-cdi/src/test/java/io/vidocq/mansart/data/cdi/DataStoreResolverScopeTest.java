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
package io.vidocq.mansart.data.cdi;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.data.repository.Repository;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * The runtime of a routed data store belongs to its CDI container: {@code vidocq dev} boots a new container, with new
 * data source beans, on every hot reload, and a runtime cached for the JVM would wrap the previous container's closed
 * data source (MANSART-041).
 */
class DataStoreResolverScopeTest {

    @Repository(dataStore = "orders")
    interface Orders {
    }

    @Repository(dataStore = "orders")
    interface OrderHistory {
    }

    @Test
    void aContainerSharesOneRuntimePerDataStore() {
        var container = new FakeContainer("shared");
        RepositoryRuntime first = DataStoreResolver.resolve(container.lookup(), Orders.class);
        RepositoryRuntime second = DataStoreResolver.resolve(container.lookup(), OrderHistory.class);
        assertSame(first, second);
    }

    @Test
    void aNewContainerDoesNotGetTheRuntimeOfThePreviousOne() {
        var before = new FakeContainer("before");
        RepositoryRuntime stale = DataStoreResolver.resolve(before.lookup(), Orders.class);
        var after = new FakeContainer("after");
        RepositoryRuntime fresh = DataStoreResolver.resolve(after.lookup(), Orders.class);
        assertNotSame(stale, fresh, "the runtime of a discarded container was served to its successor");
        assertSame(fresh, DataStoreResolver.resolve(after.lookup(), OrderHistory.class));
    }

    /** A CDI container reduced to what the resolver asks of it: the holder of its runtimes and an "orders" data source. */
    private static final class FakeContainer {
        private final Map<Class<?>, Object> beans;
        FakeContainer(String name) {
            var dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
            this.beans = Map.of(DataStoreRuntimes.class, new DataStoreRuntimes(), DataSource.class, dataSource);
        }

        @SuppressWarnings("unchecked")
        Instance<Object> lookup() {
            return (Instance<Object>) instance(null);
        }

        private Object instance(Class<?> type) {
            return Proxy.newProxyInstance(DataStoreResolverScopeTest.class.getClassLoader(), new Class<?>[] {Instance.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "select" -> instance(args.length > 0 && args[0] instanceof Class<?> selected ? selected : null);
                        case "isResolvable" -> type != null && beans.containsKey(type);
                        case "isUnsatisfied" -> type == null || !beans.containsKey(type);
                        case "get" -> beans.get(type);
                        case "toString" -> "FakeContainer.Instance(" + type + ")";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
