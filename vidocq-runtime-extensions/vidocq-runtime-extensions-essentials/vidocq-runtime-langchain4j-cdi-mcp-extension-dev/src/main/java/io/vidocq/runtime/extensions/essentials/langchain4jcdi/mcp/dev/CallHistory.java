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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The last {@value #MAX} calls of the boot (spec §3.4), newest first, in memory, published as the {@code calls}
 * table of the panel's sample with a {@link PanelSample#REPLAY_COLUMN replay} column. An immutable list behind a
 * {@code volatile}: {@code sample} reads it without a lock.
 */
final class CallHistory {

    /** How many calls are kept. */
    static final int MAX = 20;
    /** The columns of the {@code calls} table. */
    static final List<String> COLUMNS = List.of("time", "action", "outcome", "result", "ms", "arguments",
            PanelSample.REPLAY_COLUMN);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * One call.
     *
     * @param time      when it ended, in epoch milliseconds
     * @param actionId  the id of its action
     * @param label     the label of its action
     * @param arguments its arguments, masked, as compact JSON; empty for a fixed resource
     * @param summary   its summary, scrubbed
     * @param error     whether its result is an error
     * @param millis    how long it took
     * @param details   its exchange, masked
     * @param replay    its replay cell, {@code <action id> <JSON of its arguments by name>}, or empty when too long
     */
    record Call(long time, String actionId, String label, String arguments, String summary, boolean error,
                long millis, String details, String replay) {}

    private volatile List<Call> calls = List.of();

    /** Adds {@code call} first, dropping the oldest past {@value #MAX}. */
    synchronized void add(Call call) {
        List<Call> next = new ArrayList<>(MAX);
        next.add(call);
        next.addAll(calls.subList(0, Math.min(calls.size(), MAX - 1)));
        calls = List.copyOf(next);
    }

    /** The calls, newest first. */
    List<Call> calls() {
        return calls;
    }

    /** Writes the {@code calls} table. */
    void writeTo(PanelSample out) {
        List<List<String>> rows = new ArrayList<>();
        for (Call call : calls) {
            rows.add(List.of(TIME.format(Instant.ofEpochMilli(call.time())), call.label(),
                    call.error() ? "error" : "ok", call.summary(), Long.toString(call.millis()), call.arguments(),
                    call.replay()));
        }
        out.table("calls", COLUMNS, rows);
    }
}
