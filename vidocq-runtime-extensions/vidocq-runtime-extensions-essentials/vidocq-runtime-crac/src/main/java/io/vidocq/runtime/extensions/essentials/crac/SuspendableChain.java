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
package io.vidocq.runtime.extensions.essentials.crac;


import io.vidocq.runtime.spi.Suspendable;

import java.util.List;

/**
 * Drives the {@link Suspendable}s of a boot: {@link #suspend()} in the reverse of the start order,
 * {@link #resume()} in the start order. Every one is called even when an earlier one fails; the first
 * failure is rethrown, the later ones attached to it as suppressed. Free of any {@code jdk.crac} type.
 */
final class SuspendableChain {

    private final List<Suspendable> startOrder;

    SuspendableChain(List<Suspendable> startOrder) {
        this.startOrder = List.copyOf(startOrder);
    }

    void suspend() {
        RuntimeException failure = null;
        for (Suspendable s : startOrder.reversed()) {
            failure = call(s::suspend, failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    void resume() {
        RuntimeException failure = null;
        for (Suspendable s : startOrder) {
            failure = call(s::resume, failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException call(Runnable step, RuntimeException failure) {
        try {
            step.run();
        } catch (RuntimeException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
        }
        return failure;
    }

    int size() {
        return startOrder.size();
    }
}
