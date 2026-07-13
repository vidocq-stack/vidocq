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
package io.vidocq.heisenberg.cdi.moduleit;

import java.util.concurrent.atomic.AtomicInteger;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * A {@code @Retry} bean whose {@code $$Intercepted} subclass is generated at BUILD time by the Vauban
 * APT (the HeisenbergExtension BCE, on the annotation-processor path, adds the {@code @FaultToleranceBinding}
 * marker to {@code @Retry}-annotated elements via {@code @Enhancement}).
 *
 * <p>Used by {@code FaultToleranceModulePathTest} to prove that {@code @Retry} interception fires on the
 * module path with no {@code opens}: the bean AND the {@code FaultToleranceInterceptor} are instantiated
 * and field-injected in-module by the generated {@code VaubanComponentProvider} (the interceptor's
 * {@code @Inject} state-registry fields are package-private, so the in-package {@code putfield} needs no
 * opens), and the interceptor's public {@code @AroundInvoke} runs the retry policy without opens.</p>
 */
@ApplicationScoped
public class FaultToleranceService {

    public static final AtomicInteger CALLS = new AtomicInteger(0);

    /** {@code @Retry(maxRetries = 3)} → 1 initial call + 3 retries = 4 invocations before it gives up. */
    @Retry(maxRetries = 3, retryOn = RuntimeException.class, delay = 0L)
    public String call() {
        CALLS.incrementAndGet();
        throw new IllegalStateException("boom");
    }
}
