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
package io.vidocq.runtime.extensions.microprofile.knock;

import io.vidocq.knock.spi.CheckResult;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import org.eclipse.microprofile.health.HealthCheckResponse;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Writes the live values of the {@code health} panel: the last answer of each check, as Knock recorded it when a
 * probe endpoint ({@code /health}, {@code /health/live}, {@code /health/ready} or {@code /health/started}) ran it.
 *
 * <p>A health check is application code and may do I/O: the panel never calls one. It reads what Knock's registry
 * keeps in memory, {@link HealthCheckRegistry#getCheckNames} and {@link HealthCheckRegistry#getLastResults}, so a
 * check that turns DOWN shows DOWN once the next probe request has run it, not before.
 *
 * <p>One group per probe that has at least one check, {@code liveness}, {@code readiness} then {@code startup}:
 * <ul>
 *   <li>{@code status}: {@code UP} or {@code DOWN}, as the checks of the probe last answered, or absent,
 *       {@value #NEVER_CALLED}, until a probe request runs one;</li>
 *   <li>{@code up} and {@code down}: how many checks last answered UP and DOWN, over the checks of the probe,
 *       plotted by the {@code checks} chart;</li>
 *   <li>per check, under its {@link CheckKeys key}: 1 for UP, 0 for DOWN, or absent, {@value #NEVER_CALLED}; and
 *       {@code <key>.at}, when it answered;</li>
 *   <li>{@code checks}: a table of the checks, with the name of their response, their status, when they answered
 *       and their data.</li>
 * </ul>
 * Past {@value #MAX_CHECK_VALUES} checks in a group, the values of the rest are left out, in order, and an
 * {@code omitted} text says how many: the counts and the table still cover every check.
 */
final class HealthPanel {

    /** The reason a check, or a probe, has no value: no probe request ran it since the application started. */
    static final String NEVER_CALLED = "never called";

    /** The checks UP and DOWN of each probe, over time. */
    static final List<Chart> CHARTS = List.of(
            new Chart("checks", "Checks UP / DOWN", List.of(Series.area("up"), Series.stacked("down"))));

    /** The keys a group writes itself. */
    private static final Set<String> RESERVED = Set.of("status", "up", "down", "checks", "omitted");

    /** The checks whose two values fit in a group, beside the reserved keys: 64 values per scope. */
    static final int MAX_CHECK_VALUES = (64 - 5) / 2;

    /** The rows a table keeps. */
    private static final int MAX_ROWS = 100;

    private static final List<ProbeType> PROBES = List.of(ProbeType.LIVENESS, ProbeType.READINESS, ProbeType.STARTUP);
    private static final List<String> COLUMNS = List.of("check", "name", "status", "at", "data");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private HealthPanel() {}

    /**
     * Writes the panel.
     *
     * @param registry the existing registry
     * @param zone     the zone the times are shown in
     * @param sample   where the values go
     */
    static void write(HealthCheckRegistry registry, ZoneId zone, PanelSample sample) {
        Map<ProbeType, Map<String, CheckResult>> results = new HashMap<>();
        for (CheckResult result : registry.getLastResults()) {
            results.computeIfAbsent(result.probe(), p -> new HashMap<>()).put(result.name(), result);
        }
        for (ProbeType probe : PROBES) {
            TreeSet<String> names = new TreeSet<>(registry.getCheckNames(probe));
            if (!names.isEmpty()) {
                writeProbe(probe, names, results.getOrDefault(probe, Map.of()), zone, sample);
            }
        }
    }

    private static void writeProbe(ProbeType probe, TreeSet<String> names, Map<String, CheckResult> results,
            ZoneId zone, PanelSample sample) {
        PanelSample group = sample.group(probe.name().toLowerCase(Locale.ROOT));
        CheckKeys keys = new CheckKeys(RESERVED);
        int up = 0;
        int down = 0;
        int written = 0;
        List<List<String>> rows = new ArrayList<>();
        for (String name : names) {
            CheckResult result = results.get(name);
            String display = CheckKeys.display(name);
            if (result != null) {
                if (result.status() == HealthCheckResponse.Status.UP) {
                    up++;
                } else {
                    down++;
                }
            }
            if (written < MAX_CHECK_VALUES) {
                String key = keys.allocate(display);
                if (result == null) {
                    group.absent(key, NEVER_CALLED);
                } else {
                    group.gauge(key, result.status() == HealthCheckResponse.Status.UP ? 1 : 0, 1, Unit.COUNT)
                            .text(key + CheckKeys.AT, time(result, zone));
                }
                written++;
            }
            if (rows.size() < MAX_ROWS) {
                rows.add(result == null
                        ? List.of(display, "", NEVER_CALLED, "", "")
                        : List.of(display, String.valueOf(result.responseName()), result.status().name(),
                                time(result, zone), data(result.data())));
            }
        }
        if (up + down == 0) {
            group.absent("status", NEVER_CALLED);
        } else {
            group.text("status", down > 0 ? "DOWN" : "UP")
                    .gauge("up", up, names.size(), Unit.COUNT)
                    .gauge("down", down, names.size(), Unit.COUNT);
        }
        if (written < names.size()) {
            group.text("omitted", (names.size() - written) + " checks omitted");
        }
        group.table("checks", COLUMNS, rows);
    }

    private static String time(CheckResult result, ZoneId zone) {
        return TIME.format(result.observedAt().atZone(zone));
    }

    /** {@code pool=8, ok=true}, by key. */
    private static String data(Map<String, String> data) {
        StringJoiner joined = new StringJoiner(", ");
        new TreeSet<>(data.keySet()).forEach(key -> joined.add(key + "=" + data.get(key)));
        return joined.toString();
    }
}
