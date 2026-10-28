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
package io.vidocq.heisenberg.examples;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * A service whose remote call fails transiently: {@code @Retry} replays it on {@link IOException}
 * until it succeeds, up to three retries 100 ms apart (MP FT 4.1 §3). In an application, make it a
 * CDI bean (for instance {@code @ApplicationScoped}) so that the Heisenberg interceptor applies.
 */
public class RetryExample {

    private static final int FAILING_ATTEMPTS = 2;

    private final AtomicInteger attempts = new AtomicInteger();

    /**
     * Fails on the first two attempts, then answers.
     *
     * @return the answer of the remote service
     * @throws IOException when the remote service is unreachable
     */
    @Retry(maxRetries = 3, delay = 100, retryOn = IOException.class)
    public String fetch() throws IOException {
        if (attempts.incrementAndGet() <= FAILING_ATTEMPTS) {
            throw new IOException("remote service unreachable");
        }
        return "answer";
    }
}
