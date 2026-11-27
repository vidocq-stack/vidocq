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
package io.vidocq.runtime.extensions.jakartaee.web.naming;

import io.vidocq.vauban.naming.JndiName;
import io.vidocq.vauban.naming.JndiNameProvider;
import io.vidocq.vauban.naming.Namespace;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import javax.naming.NamingException;
import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * The names the application's {@code DataSource} beans answer without a declaration, as the data sources of an
 * application server do (Vidocq/vidocq#676): {@code java:comp/DefaultDataSource} (Jakarta EE 11 Platform §5.19) is
 * the {@code @Default} one, the default Mansart pool, and {@code java:comp/env/<name>} the {@code @Named("<name>")}
 * one, the pool {@code vidocq.pool.<name>.url} declares. A persistence unit whose {@code jta-data-source} names a
 * pool, a library or the application finds it with a plain JNDI lookup.
 *
 * <p>When the application has transactions (Mansart Transactions), a name answers the pool through Mansart's
 * {@code JtaDataSource}: within a transaction its connections are the one connection of that transaction and that
 * pool, which the transaction manager alone commits or rolls back; outside a transaction they are the pool's own.
 * Without transactions, a name answers the pool itself.
 *
 * <p>A link of {@code vidocq.properties} to one of these names answers what that name answers (Vidocq/vidocq#831):
 * with {@code vidocq.naming.comp/env/jdbc/orders = java:comp/env/orders}, a server name, the lookup of
 * {@code java:comp/env/jdbc/orders} returns the {@code orders} pool through the transactions, as
 * {@code java:comp/env/orders} does. {@link ConfiguredNames} leaves such a link to this provider.
 *
 * <p>Any other name {@code vidocq.properties} declares ({@code vidocq.naming.*}) is not asked here: declared names
 * come first. A bean declaration binds a name to the pool itself, which never enlists:
 * {@code vidocq.naming.comp/env/jdbc/orders-raw = javax.sql.DataSource @Named("orders")}.
 */
@Singleton
public class PoolNames implements JndiNameProvider {

    private static final String DEFAULT_DATA_SOURCE = "DefaultDataSource";
    private static final String ENVIRONMENT = "env";

    /** The {@code DataSource} beans of the application. */
    interface Pools {
        Optional<DataSource> named(String name);

        Optional<DataSource> byDefault();

        /** The names of the {@code @Named} ones. */
        Set<String> names();
    }

    private final Pools pools;
    private final UnaryOperator<DataSource> transactional;

    /**
     * The names of the pools of the running container.
     *
     * @param beanManager the running container
     */
    @Inject
    public PoolNames(BeanManager beanManager) {
        this(new BeanPools(beanManager), transactional(beanManager, Thread.currentThread().getContextClassLoader()));
    }

    PoolNames(Pools pools, UnaryOperator<DataSource> transactional) {
        this.pools = pools;
        this.transactional = transactional;
    }

    @Override
    public Optional<Object> lookup(JndiName name) throws NamingException {
        try {
            return pool(ConfiguredNames.linkedTo(name.toString()).orElse(name)).map(transactional);
        } catch (IllegalStateException ambiguous) {
            NamingException failure = new NamingException(name + ": " + ambiguous.getMessage());
            failure.setRootCause(ambiguous);
            throw failure;
        }
    }

    /**
     * Whether a pool answers this name, which a link of {@code vidocq.properties} may then name.
     *
     * @param name a name, such as {@code java:comp/env/orders}
     * @return {@code true} when a pool answers it
     * @throws IllegalStateException when several {@code DataSource} beans have that name
     */
    boolean answers(JndiName name) {
        return pool(name).isPresent();
    }

    /** The pool a name of {@code java:comp} names, if any. */
    private Optional<DataSource> pool(JndiName name) {
        if (name.namespace() != Namespace.COMP) {
            return Optional.empty();
        }
        String path = name.path();
        if (path.equals(DEFAULT_DATA_SOURCE)) {
            return pools.byDefault();
        }
        return path.startsWith(ENVIRONMENT + "/") ? pools.named(path.substring(ENVIRONMENT.length() + 1))
                : Optional.empty();
    }

    /**
     * {@code java:comp/env}, each prefix of a pool name, such as {@code java:comp/env/jdbc}, and each prefix of a
     * declared name, such as {@code java:app/env/jdbc} for a link {@code java:app/env/jdbc/orders} this provider
     * answers.
     */
    @Override
    public boolean isContext(JndiName name) {
        if (ConfiguredNames.names().stream().anyMatch(declared -> isUnder(declared, name))) {
            return true;
        }
        if (name.namespace() != Namespace.COMP || !(name.path() + "/").startsWith(ENVIRONMENT + "/")) {
            return false;
        }
        String prefix = name.path().length() == ENVIRONMENT.length() ? ""
                : name.path().substring(ENVIRONMENT.length() + 1) + "/";
        return pools.names().stream().anyMatch(pool -> pool.startsWith(prefix));
    }

    /** Whether a declared name is under {@code context}, segment by segment. */
    private static boolean isUnder(String declared, JndiName context) {
        return declared.startsWith(context + "/");
    }

    /**
     * How a pool takes part in the transactions of the application: through Mansart's {@code JtaDataSource} when the
     * container has a transaction manager, as is otherwise. The transaction manager is named as text, as
     * {@link PlatformNames} names its beans: an application without transactions has neither the type nor Mansart
     * Transactions, and {@link Enlisting}, the only class that names them, is then never loaded.
     */
    private static UnaryOperator<DataSource> transactional(BeanManager beanManager, ClassLoader loader) {
        boolean transactions = PlatformNames.bean("jakarta.transaction.TransactionManager", loader)
                .filter(bean -> !beanManager.getBeans(bean.type()).isEmpty())
                .isPresent();
        return transactions ? Enlisting.over(beanManager) : UnaryOperator.identity();
    }

    /** The {@code DataSource} beans, as CDI resolves them. */
    private record BeanPools(BeanManager beanManager) implements Pools {

        @Override
        public Optional<DataSource> named(String name) {
            return resolve(NamedLiteral.of(name), "@Named(\"" + name + "\")");
        }

        @Override
        public Optional<DataSource> byDefault() {
            return resolve(Default.Literal.INSTANCE, "@Default");
        }

        @Override
        public Set<String> names() {
            Set<String> names = new TreeSet<>();
            for (Bean<?> bean : beanManager.getBeans(DataSource.class, Any.Literal.INSTANCE)) {
                for (Annotation qualifier : bean.getQualifiers()) {
                    if (qualifier instanceof Named named && !named.value().isEmpty()) {
                        names.add(named.value());
                    }
                }
            }
            return names;
        }

        private Optional<DataSource> resolve(Annotation qualifier, String written) {
            Instance<DataSource> candidates = beanManager.createInstance().select(DataSource.class, qualifier);
            if (candidates.isAmbiguous()) {
                throw new IllegalStateException("several javax.sql.DataSource beans are " + written);
            }
            return candidates.isUnsatisfied() ? Optional.empty() : Optional.of(candidates.get());
        }
    }
}
