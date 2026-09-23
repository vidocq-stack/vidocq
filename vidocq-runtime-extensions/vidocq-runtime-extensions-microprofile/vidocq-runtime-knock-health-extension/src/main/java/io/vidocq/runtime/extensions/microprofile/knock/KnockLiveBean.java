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
package io.vidocq.runtime.extensions.microprofile.knock;

import io.vidocq.knock.spi.HealthCheckRegistry;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * The {@link HealthCheckRegistry} bean, resolved once in {@link KnockHealthExtension#onStart} and never created: the
 * immutable snapshot the {@code health} section and panel read.
 *
 * <p><b>Why bean metadata rather than a proxy.</b> Knock's registry bean is application scoped: calling a client proxy
 * of it from the dev console would create it. A panel measures, it does not change what it measures. So this record
 * keeps the {@link Bean} metadata only, and asks the bean's {@link Context} for the <em>existing</em> instance with
 * the single-argument {@link Context#get(jakarta.enterprise.context.spi.Contextual)}, which returns {@code null}
 * rather than creating one. Knock's registrar creates the registry when it registers the first check, as the
 * application context starts: a registry that does not exist yet holds no check.
 *
 * @param beans    the bean manager of the started container, {@code null} for {@link #NONE}
 * @param registry the {@link HealthCheckRegistry} bean, or {@code null} when the container has none
 */
record KnockLiveBean(BeanManager beans, Bean<?> registry) {

    /** Before {@code onStart} and after {@code onStop}: nothing to read. */
    static final KnockLiveBean NONE = new KnockLiveBean(null, null);

    /** Before {@code onStart}, or after {@code onStop}. */
    static final String NOT_STARTED = "not started";
    /** No registry bean in this container, or more than one. */
    static final String NOT_DEPLOYED = "no Knock registry in this container";
    /** The registry exists as a bean, but no check was ever registered in it. */
    static final String NOT_CREATED_YET = "registry not created yet";

    /**
     * Resolves the registry bean, once, without creating it.
     *
     * @param beans the bean manager of the started container
     * @return the bean, which may be absent; {@link #NONE} when there is no bean manager
     */
    static KnockLiveBean of(BeanManager beans) {
        if (beans == null) {
            return NONE;
        }
        try {
            return new KnockLiveBean(beans, beans.resolve(beans.getBeans(HealthCheckRegistry.class)));
        } catch (RuntimeException | LinkageError unresolvable) {
            return new KnockLiveBean(beans, null);
        }
    }

    /**
     * The existing registry, or {@code null} when there is none: the single-argument
     * {@link Context#get(jakarta.enterprise.context.spi.Contextual)} never creates it. An inactive context reads as
     * no instance.
     *
     * @return the registry, or {@code null}; {@link #absence()} then says why
     */
    HealthCheckRegistry read() {
        if (beans == null || registry == null) {
            return null;
        }
        try {
            Context context = beans.getContext(registry.getScope());
            return context.get(registry) instanceof HealthCheckRegistry existing ? existing : null;
        } catch (RuntimeException | LinkageError noInstance) {
            return null;
        }
    }

    /**
     * Why {@link #read()} gives nothing.
     *
     * @return the reason, shown greyed in the panel
     */
    String absence() {
        if (beans == null) {
            return NOT_STARTED;
        }
        return registry == null ? NOT_DEPLOYED : NOT_CREATED_YET;
    }
}
