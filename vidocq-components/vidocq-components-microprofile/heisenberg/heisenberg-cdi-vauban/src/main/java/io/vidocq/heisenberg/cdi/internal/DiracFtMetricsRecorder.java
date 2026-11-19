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
package io.vidocq.heisenberg.cdi.internal;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryScope;

/**
 * MicroProfile Fault Tolerance 4.1 §9 metrics recorder bean (MicroProfile Metrics, through Dirac).
 * Publishes through {@link DiracFtMetrics} when the MicroProfile Metrics API is present, and does
 * nothing otherwise: this class refers to no Metrics type, so an application without Metrics
 * still starts (BUG-005).
 */
@ApplicationScoped
public class DiracFtMetricsRecorder extends DelegatingFtMetricsRecorder {

    private static final boolean METRICS_PRESENT =
            apiPresent("org.eclipse.microprofile.metrics.MetricRegistry");

    // The base-scope registry, looked up only once the Metrics API is known to be there. The field
    // type names no Metrics type. Its qualifier does, but only as an annotation: without the Metrics
    // API, reflection drops it and the field is a plain Instance<Object>; with it, the registry
    // producer reads the scope from this injection point (RegistryScope.scope is @Nonbinding).
    // MetricRegistry.BASE_SCOPE is a compile-time constant, inlined by javac.
    // Package-private (not private): the co-located _VaubanComponents provider assigns it with an
    // in-package putfield, so Vauban needs no `opens … to io.vidocq.vauban.core` for field injection.
    @Inject
    @RegistryScope(scope = MetricRegistry.BASE_SCOPE)
    Instance<Object> baseRegistry;

    @PostConstruct
    void init() {
        if (METRICS_PRESENT) {
            delegateTo(DiracFtMetrics.create(baseRegistry));
        }
    }
}
