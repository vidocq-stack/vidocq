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
package io.vidocq.heisenberg.it.openliberty;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.faulttolerance.Bulkhead;

@ApplicationScoped
public class BulkheadService {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** One call at a time: the first one holds the only slot until released. */
    @Bulkhead(1)
    public String guarded() throws InterruptedException {
        entered.countDown();
        release.await(10, TimeUnit.SECONDS);
        return "done";
    }

    public boolean awaitEntered() throws InterruptedException {
        return entered.await(5, TimeUnit.SECONDS);
    }

    public void release() {
        release.countDown();
    }
}
