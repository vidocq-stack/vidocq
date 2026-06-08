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
package io.vidocq.humboldt.sdk.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous result of an export or flush operation — functional equivalent
 * of {@code io.opentelemetry.sdk.common.CompletableResultCode} without a
 * dependency on the third-party OTel SDK.
 *
 * <p>Three states: pending, success, failure. Atomic, terminal transitions.</p>
 */
public final class CompletableResultCode {

    private static final CompletableResultCode SUCCESS = new CompletableResultCode().succeed();
    private static final CompletableResultCode FAILURE = new CompletableResultCode().fail();

    private final AtomicReference<Boolean> result = new AtomicReference<>(null);
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean callbacksFired = new AtomicBoolean(false);
    private final List<Runnable> onSuccess = new ArrayList<>();
    private final List<Runnable> onFailure = new ArrayList<>();

    public static CompletableResultCode ofSuccess() {
        return SUCCESS;
    }

    public static CompletableResultCode ofFailure() {
        return FAILURE;
    }

    public static CompletableResultCode ofAll(List<CompletableResultCode> codes) {
        if (codes.isEmpty()) return ofSuccess();
        CompletableResultCode agg = new CompletableResultCode();
        int total = codes.size();
        AtomicReference<Integer> remaining = new AtomicReference<>(total);
        AtomicBoolean anyFailed = new AtomicBoolean(false);
        for (CompletableResultCode c : codes) {
            c.whenComplete(() -> {
                if (!c.isSuccess()) anyFailed.set(true);
                int left = remaining.updateAndGet(i -> i - 1);
                if (left == 0) {
                    if (anyFailed.get()) agg.fail();
                    else agg.succeed();
                }
            });
        }
        return agg;
    }

    public CompletableResultCode succeed() {
        if (result.compareAndSet(null, Boolean.TRUE)) {
            latch.countDown();
            fireCallbacks(true);
        }
        return this;
    }

    public CompletableResultCode fail() {
        if (result.compareAndSet(null, Boolean.FALSE)) {
            latch.countDown();
            fireCallbacks(false);
        }
        return this;
    }

    public boolean isDone() {
        return result.get() != null;
    }

    public boolean isSuccess() {
        return Boolean.TRUE.equals(result.get());
    }

    public CompletableResultCode whenComplete(Runnable action) {
        synchronized (this) {
            if (!isDone()) {
                onSuccess.add(action);
                onFailure.add(action);
                return this;
            }
        }
        action.run();
        return this;
    }

    public CompletableResultCode join(long timeout, TimeUnit unit) {
        try {
            latch.await(timeout, unit);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return this;
    }

    private void fireCallbacks(boolean success) {
        if (!callbacksFired.compareAndSet(false, true)) return;
        List<Runnable> toFire;
        synchronized (this) {
            toFire = success ? new ArrayList<>(onSuccess) : new ArrayList<>(onFailure);
            onSuccess.clear();
            onFailure.clear();
        }
        for (Runnable r : toFire) {
            r.run();
        }
    }
}
