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
package io.vidocq.runtime.tck.ft;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.fault.tolerance.tck.metrics.util.MetricRegistryProxy;
import org.eclipse.microprofile.fault.tolerance.tck.metrics.util.MetricRegistryProxyHandler;
import org.eclipse.microprofile.metrics.MetricRegistry;

import java.lang.reflect.Proxy;

/**
 * TCK glue: exposes the implementation's {@link MetricRegistry} through the
 * Fault Tolerance TCK's own {@link MetricRegistryProxy} interface. The TCK's
 * {@code MetricGetter} resolves an unqualified proxy via CDI; the TCK ships the
 * proxy types and a {@code @RegistryType(BASE)} producer, but none for the
 * unqualified case — every runner must provide this bridge. Only TCK util
 * types and the MP Metrics API are involved: the runtime under test is not
 * modified. Added to each deployment by {@link FtTckArchiveProcessor}.
 *
 * <p>FT metrics land in the application-scope registry
 * ({@code DiracFtMetricsRecorder}); MP Metrics 5 deprecated the
 * BASE/APPLICATION/VENDOR split, so both producers expose that same registry.</p>
 */
@ApplicationScoped
public class FtMetricsRegistryProxyProducer {

    @Inject
    MetricRegistry applicationRegistry;

    @Produces
    public MetricRegistryProxy produce() {
        return buildProxy();
    }

    private MetricRegistryProxy buildProxy() {
        return (MetricRegistryProxy) Proxy.newProxyInstance(
                MetricRegistryProxy.class.getClassLoader(),
                new Class<?>[] { MetricRegistryProxy.class },
                new MetricRegistryProxyHandler(applicationRegistry));
    }
}
