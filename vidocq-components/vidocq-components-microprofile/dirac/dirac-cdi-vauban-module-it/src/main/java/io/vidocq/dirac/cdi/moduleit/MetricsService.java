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
package io.vidocq.dirac.cdi.moduleit;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.metrics.annotation.Counted;

/**
 * A {@code @Counted} bean whose {@code $$Intercepted} subclass is generated at BUILD time by the
 * Vauban APT (the DiracExtension BCE, on the annotation-processor path, registers {@code @Counted} as
 * an interceptor binding).
 *
 * <p>It carries {@code @Counted} at <strong>both</strong> levels on purpose:
 * <ul>
 *   <li><b>class level</b> makes the constructor an intercepted target, so {@code @AroundConstruct}
 *       on {@code CountedInterceptor} fires — proving the construct-interception chain runs on the
 *       module path with no {@code opens} (the interceptor is instantiated in-module by the generated
 *       provider, and its public {@code @AroundConstruct} is reachable without opens). That callback's
 *       {@code preRegisterCounters} is what registers the counter below;</li>
 *   <li><b>method level</b> on {@link #ping()} drives {@code @AroundInvoke} and pins the asserted
 *       metric id ({@code moduleit.counted.calls}).</li>
 * </ul>
 *
 * <p>Used by {@code MetricsModulePathTest} to prove that {@code @Counted} interception — both
 * {@code @AroundConstruct} and {@code @AroundInvoke} — fires on the module path with no {@code opens}:
 * the bean AND the {@code CountedInterceptor} are instantiated and field-injected in-module by the
 * generated {@code VaubanComponentProvider}.</p>
 */
@ApplicationScoped
@Counted
public class MetricsService {

    @Counted(name = "moduleit.counted.calls", absolute = true, tags = {"source=module"})
    public String ping() {
        return "pong";
    }
}
