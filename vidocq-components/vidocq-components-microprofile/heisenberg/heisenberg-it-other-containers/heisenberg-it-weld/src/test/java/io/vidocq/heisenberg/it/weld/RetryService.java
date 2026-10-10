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
package io.vidocq.heisenberg.it.weld;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Retry;

@ApplicationScoped
public class RetryService {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger configuredCalls = new AtomicInteger();

    /** Fails twice, then succeeds: two retries. */
    @Retry(maxRetries = 3, delay = 0, jitter = 0)
    public String flaky() {
        int attempt = calls.incrementAndGet();
        if (attempt < 3) {
            throw new IllegalStateException("attempt " + attempt);
        }
        calls.set(0);
        return "ok after " + attempt;
    }

    /** Always fails; microprofile-config.properties lowers maxRetries for this method to 1. */
    @Retry(maxRetries = 5, delay = 0, jitter = 0)
    public String configured() {
        configuredCalls.incrementAndGet();
        throw new IllegalStateException("always");
    }

    public int configuredCalls() {
        return configuredCalls.get();
    }
}
