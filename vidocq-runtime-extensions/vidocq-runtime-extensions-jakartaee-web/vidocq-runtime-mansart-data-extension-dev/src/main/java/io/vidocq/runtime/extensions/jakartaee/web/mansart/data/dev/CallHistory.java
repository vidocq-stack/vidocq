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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last {@value #MAX} calls of each repository this boot (spec §8), newest first, in memory. They are published as
 * one {@code calls} table with a {@link PanelSample#REPLAY_COLUMN replay} column, which the page moves to the
 * repository tabs, each keeping the rows of its own actions. Immutable lists behind a {@code volatile}:
 * {@code sample} reads them without a lock. A dev reload builds a new history with the new actions.
 */
final class CallHistory {

    /** How many calls of one repository are kept. */
    static final int MAX = 20;
    /** The longest {@code arguments} cell. */
    static final int MAX_ARGUMENTS = 200;
    /** The most rows of the table: the console keeps 100 rows per table. */
    static final int MAX_ROWS = 100;
    /** The columns of the {@code calls} table. */
    static final List<String> COLUMNS = List.of("time", "method", "outcome", "ms", "arguments",
            PanelSample.REPLAY_COLUMN);

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * One call.
     *
     * @param sequence  its rank in the boot, newest highest
     * @param time      when it ended, in epoch milliseconds
     * @param method    the label of its action
     * @param outcome   its summary, {@code error: } first for an error
     * @param millis    how long it took
     * @param arguments the JSON sent, cut at {@value #MAX_ARGUMENTS} characters
     * @param replay    {@code <action id> <JSON of its arguments by name>}, or empty past
     *                  {@link PanelSample#MAX_REPLAY_CELL}
     */
    record Call(long sequence, long time, String method, String outcome, long millis, String arguments,
                String replay) {}

    private long sequence;
    private volatile Map<String, List<Call>> calls = Map.of();

    /** Adds a call of the repository whose group is {@code group}, first, dropping its oldest past {@value #MAX}. */
    synchronized void add(String group, long time, String method, String outcome, long millis, String arguments,
                          String replay) {
        Call call = new Call(++sequence, time, method, outcome, millis, Failures.cut(arguments, MAX_ARGUMENTS),
                replay.length() > PanelSample.MAX_REPLAY_CELL ? "" : replay);
        Map<String, List<Call>> next = new HashMap<>(calls);
        List<Call> previous = next.getOrDefault(group, List.of());
        List<Call> kept = new ArrayList<>(MAX);
        kept.add(call);
        kept.addAll(previous.subList(0, Math.min(previous.size(), MAX - 1)));
        next.put(group, List.copyOf(kept));
        calls = Map.copyOf(next);
    }

    /** The calls of {@code group}, newest first. */
    List<Call> calls(String group) {
        return calls.getOrDefault(group, List.of());
    }

    /** Writes the {@code calls} table: every repository's calls, newest first, {@value #MAX_ROWS} at most. */
    void writeTo(PanelSample out) {
        List<List<String>> rows = calls.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> Long.compare(b.sequence(), a.sequence()))
                .limit(MAX_ROWS)
                .map(call -> List.of(TIME.format(Instant.ofEpochMilli(call.time())), call.method(), call.outcome(),
                        Long.toString(call.millis()), call.arguments(), call.replay()))
                .toList();
        out.table("calls", COLUMNS, rows);
    }
}
