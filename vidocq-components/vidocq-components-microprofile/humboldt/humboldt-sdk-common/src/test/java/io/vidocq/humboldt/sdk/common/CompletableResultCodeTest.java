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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletableResultCodeTest {

    @Test
    void new_code_is_pending() {
        CompletableResultCode code = new CompletableResultCode();
        assertFalse(code.isDone());
        assertFalse(code.isSuccess());
    }

    @Test
    void ofSuccess_and_ofFailure_are_done() {
        assertTrue(CompletableResultCode.ofSuccess().isDone());
        assertTrue(CompletableResultCode.ofSuccess().isSuccess());
        assertTrue(CompletableResultCode.ofFailure().isDone());
        assertFalse(CompletableResultCode.ofFailure().isSuccess());
    }

    @Test
    void first_transition_wins() {
        CompletableResultCode code = new CompletableResultCode().succeed();
        code.fail();
        assertTrue(code.isSuccess());

        CompletableResultCode failed = new CompletableResultCode().fail();
        failed.succeed();
        assertTrue(failed.isDone());
        assertFalse(failed.isSuccess());
    }

    @Test
    void whenComplete_runs_once_on_completion() {
        CompletableResultCode code = new CompletableResultCode();
        AtomicInteger calls = new AtomicInteger();
        assertSame(code, code.whenComplete(calls::incrementAndGet));
        assertEquals(0, calls.get());
        code.succeed();
        code.fail();
        assertEquals(1, calls.get());
    }

    @Test
    void whenComplete_on_a_done_code_runs_immediately() {
        AtomicInteger calls = new AtomicInteger();
        new CompletableResultCode().fail().whenComplete(calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void ofAll_of_nothing_is_success() {
        assertTrue(CompletableResultCode.ofAll(List.of()).isSuccess());
    }

    @Test
    void ofAll_succeeds_when_every_code_succeeds() {
        CompletableResultCode a = new CompletableResultCode();
        CompletableResultCode b = new CompletableResultCode();
        CompletableResultCode all = CompletableResultCode.ofAll(List.of(a, b));
        a.succeed();
        assertFalse(all.isDone());
        b.succeed();
        assertTrue(all.isSuccess());
    }

    @Test
    void ofAll_fails_when_any_code_fails() {
        CompletableResultCode a = new CompletableResultCode();
        CompletableResultCode all = CompletableResultCode.ofAll(List.of(a, CompletableResultCode.ofSuccess()));
        a.fail();
        assertTrue(all.isDone());
        assertFalse(all.isSuccess());
    }

    @Test
    void join_returns_once_the_code_completes() {
        CompletableResultCode code = new CompletableResultCode();
        Thread.ofVirtual().start(code::succeed);
        assertSame(code, code.join(10, TimeUnit.SECONDS));
        assertTrue(code.isSuccess());
    }

    @Test
    void join_fails_the_code_when_it_times_out() {
        // Same contract as io.opentelemetry.sdk.common.CompletableResultCode#join:
        // "If it times out or is interrupted, the CompletableResultCode is failed."
        CompletableResultCode code = new CompletableResultCode();
        AtomicInteger calls = new AtomicInteger();
        code.whenComplete(calls::incrementAndGet);
        code.join(10, TimeUnit.MILLISECONDS);
        assertTrue(code.isDone());
        assertFalse(code.isSuccess());
        assertEquals(1, calls.get());
    }

    @Test
    void join_fails_the_code_and_keeps_the_interrupt_flag_when_interrupted() {
        CompletableResultCode code = new CompletableResultCode();
        Thread.currentThread().interrupt();
        try {
            code.join(10, TimeUnit.SECONDS);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(code.isDone());
        assertFalse(code.isSuccess());
    }
}
