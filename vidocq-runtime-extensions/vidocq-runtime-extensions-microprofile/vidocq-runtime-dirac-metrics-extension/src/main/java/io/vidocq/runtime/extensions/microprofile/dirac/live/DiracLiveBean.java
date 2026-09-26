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
package io.vidocq.runtime.extensions.microprofile.dirac.live;

import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * The bean that holds Dirac's registries, resolved once in
 * {@link io.vidocq.runtime.extensions.microprofile.dirac.DiracMetricsExtension#onStart} and never created: the
 * immutable snapshot the {@code metrics} section and panel read.
 *
 * <p><b>Why bean metadata rather than a proxy.</b> {@link MetricRegistryProducerBean} is application scoped: calling
 * a client proxy of it from the dev console would create it, and with it the base registry and its gauges, on a
 * server whose application never asked for a metric. A panel measures, it does not change what it measures. So this
 * record keeps the {@link Bean} metadata only, and asks the bean's {@link Context} for the <em>existing</em> instance
 * with the single-argument {@link Context#get(jakarta.enterprise.context.spi.Contextual)}, which returns
 * {@code null} rather than creating one.
 *
 * @param beans    the bean manager of the started container, {@code null} for {@link #NONE}
 * @param producer the {@link MetricRegistryProducerBean} bean, or {@code null} when the container has none
 */
public record DiracLiveBean(BeanManager beans, Bean<?> producer) {

    /** Before {@code onStart} and after {@code onStop}: nothing to read. */
    public static final DiracLiveBean NONE = new DiracLiveBean(null, null);

    /** Before {@code onStart}, or after {@code onStop}. */
    public static final String NOT_STARTED = "not started";
    /**
     * No producer bean in this container, or another copy of its class, loaded by another loader, which this
     * extension cannot read.
     */
    public static final String NOT_DEPLOYED = "no Dirac registry in this container";
    /** The producer exists as a bean, but nothing has asked for a registry or a metric yet. */
    public static final String NOT_CREATED_YET = "not created yet";

    /**
     * Resolves the producer bean, once, without creating it.
     *
     * @param beans the bean manager of the started container
     * @return the bean, which may be absent; {@link #NONE} when there is no bean manager
     */
    public static DiracLiveBean of(BeanManager beans) {
        if (beans == null) {
            return NONE;
        }
        try {
            Bean<?> bean = beans.resolve(beans.getBeans(MetricRegistryProducerBean.class));
            // The identity check keeps out another copy of the class, whose instance a cast would reject.
            return new DiracLiveBean(beans,
                    bean != null && bean.getBeanClass() == MetricRegistryProducerBean.class ? bean : null);
        } catch (RuntimeException | LinkageError unresolvable) {
            return new DiracLiveBean(beans, null);
        }
    }

    /**
     * The registries of the existing producer, or {@code null} when there is none: the single-argument
     * {@link Context#get(jakarta.enterprise.context.spi.Contextual)} never creates it. An inactive context reads as
     * no instance.
     *
     * @return the registries, or {@code null}; {@link #absence()} then says why
     */
    public DiracRegistries registries() {
        if (beans == null || producer == null) {
            return null;
        }
        try {
            Context context = beans.getContext(producer.getScope());
            return context.get(producer) instanceof MetricRegistryProducerBean existing
                    ? DiracRegistries.of(existing)
                    : null;
        } catch (RuntimeException | LinkageError noInstance) {
            return null;
        }
    }

    /**
     * Why {@link #registries()} gave nothing.
     *
     * @return {@value #NOT_STARTED}, {@value #NOT_DEPLOYED} or {@value #NOT_CREATED_YET}
     */
    public String absence() {
        if (beans == null) {
            return NOT_STARTED;
        }
        return producer == null ? NOT_DEPLOYED : NOT_CREATED_YET;
    }
}
