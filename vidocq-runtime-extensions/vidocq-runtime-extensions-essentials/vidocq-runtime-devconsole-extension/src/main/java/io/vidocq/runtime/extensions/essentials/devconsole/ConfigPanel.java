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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.StartupReportView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The console's own {@code config} panel, shown after the contributed panels and before the {@linkplain CdiPanel CDI
 * container}: which keys the application sets, which source each value comes from, and which keys nothing reads.
 *
 * <ul>
 *   <li><b>Boot facts:</b> a summary, such as {@code 12 keys: 5 of the application, 6 vidocq.*, 1 mp.*; 4 sources};
 *       then {@code sources}, each with its ordinal, in lookup order; {@code values}, what is shown of them;
 *       {@code status}, what the last column means; {@code keys left out} when the table could not show every key;
 *       and {@code source}.</li>
 *   <li><b>Table</b> {@code keys}: key, source, ordinal, value and status, the application's keys first, then
 *       {@code vidocq.*}, then {@code mp.*}, {@value ConfigInventory#MAX_ROWS} rows at most (see
 *       {@link ConfigInventory} for which keys). No key is written absent.</li>
 * </ul>
 *
 * <p><b>Values.</b> In a dev launch only, and made safe in this JVM before anything reaches the page: a key whose
 * last segment names a secret reads {@code configured}, and a URL loses its credentials (see {@link ConfigValues}).
 * Outside a dev launch, every value reads {@code not shown outside dev}; the keys and their sources are still listed.
 *
 * <p><b>Status.</b> What the key audit of the boot, {@code VIDOCQ-CFG-003}, says of the key: {@code unused} when an
 * anomaly of that code names it, {@code claimed} when it falls under a namespace the audit covers, that is one the
 * core or a loaded extension reads, and {@code not audited} otherwise. The panel reads it from the startup report,
 * the anomalies and the {@code audited} namespaces of the core's {@code configuration} section, rather than from the
 * extensions themselves, which the console cannot reach. The report is written after every {@code onStart}, the
 * console's included, so the status reads {@code report not written yet} until then; the first sample that finds
 * the report computes the rows once and keeps them.
 *
 * <p>The configuration is read once, in the console's {@code onStart}: it does not change during a boot, and a dev
 * reload boots again. The panel keeps strings only and is dropped with the console's snapshot in {@code onStop}.
 */
final class ConfigPanel implements DevConsolePanel {

    /** The panel's id, reserved by the core for the console. */
    static final String ID = "config";

    /** The columns of the {@code keys} table. */
    static final List<String> COLUMNS = List.of("key", "source", "ordinal", "value", "status");

    /** The code of the key audit's anomaly: a configured key that nothing reads. */
    static final String UNREAD_KEY = "VIDOCQ-CFG-003";

    static final String UNUSED = "unused";
    static final String CLAIMED = "claimed";
    static final String NOT_AUDITED = "not audited";
    static final String NOT_WRITTEN = "report not written yet";

    private static final String KEYS = "keys";
    private static final String NONE = "none";
    /** How the key audit's message quotes the key: {@code Configuration key 'vidocq.x' is read by nothing...}. */
    private static final String QUOTE_START = "Configuration key '";
    private static final String QUOTE_END = "' is read by nothing";

    /** What the configuration held at boot, or {@code null} when there was none to read. */
    private final ConfigInventory inventory;
    /** The startup report of the boot, once written. */
    private final Supplier<Optional<StartupReportView>> report;
    /** The rows with their status, once the report was found; {@code null} until then. */
    private volatile List<List<String>> audited;

    /**
     * @param inventory what the configuration held at boot, or {@code null} when there was none
     * @param report    the startup report of the boot, once written
     */
    ConfigPanel(ConfigInventory inventory, Supplier<Optional<StartupReportView>> report) {
        this.inventory = inventory;
        this.report = report;
    }

    /**
     * The panel of {@code config}, read now.
     *
     * @param config the configuration of the boot, or {@code null}
     * @param mode   the launch mode: the values are shown in {@link LaunchMode#DEV} only
     * @param report the startup report of the boot, once written
     */
    static ConfigPanel of(VidocqConfig config, LaunchMode mode, Supplier<Optional<StartupReportView>> report) {
        return new ConfigPanel(config == null ? null : ConfigInventory.read(config, mode), report);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Configuration";
    }

    /** The counts, the sources in lookup order, and what the values and the status show. Never a value. */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        ConfigInventory held = inventory;
        if (held == null) {
            section.summary("not available: no configuration");
            return;
        }
        section.summary(count(held.keys(), "key") + ": " + held.application() + " of the application, "
                        + held.vidocq() + " vidocq.*, " + held.mp() + " mp.*; "
                        + count(held.sources().size(), "source"))
                .list("sources", held.sources())
                .row("values", held.valuesShown() ? "shown, a secret as configured and a URL without its credentials"
                        : ConfigValues.NOT_SHOWN)
                .row("status", "unused: named by a VIDOCQ-CFG-003 anomaly; claimed: under a namespace the core or an"
                        + " extension reads; not audited: any other key");
        if (held.keys() > held.rows().size()) {
            section.row("keys left out", (held.keys() - held.rows().size()) + ", past the "
                    + ConfigInventory.MAX_ROWS + " rows the table shows");
        }
        section.row("source", "the configuration sources, read once at boot: each key's raw value in the first"
                + " source that has one");
    }

    /** The {@code keys} table, its status read from the report once it is written. */
    @Override
    public void sample(PanelSample sample) {
        ConfigInventory held = inventory;
        if (held == null) {
            return;
        }
        if (held.rows().isEmpty()) {
            sample.absent(KEYS, NONE);
            return;
        }
        sample.table(KEYS, COLUMNS, rows(held));
    }

    /** The rows with their status: kept once the report is found, the inventory's with no status until then. */
    private List<List<String>> rows(ConfigInventory held) {
        List<List<String>> kept = audited;
        if (kept != null) {
            return kept;
        }
        Optional<StartupReportView> written;
        try {
            written = report.get();
        } catch (RuntimeException unreadable) {
            written = Optional.empty();
        }
        if (written.isEmpty()) {
            return withStatus(held.rows(), key -> NOT_WRITTEN);
        }
        List<List<String>> rows = withStatus(held.rows(), Audit.of(written.get())::status);
        audited = rows;
        return rows;
    }

    private static List<List<String>> withStatus(List<List<String>> rows, Function<String, String> status) {
        List<List<String>> out = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>(row);
            cells.add(status.apply(row.getFirst()));
            out.add(List.copyOf(cells));
        }
        return List.copyOf(out);
    }

    private static String count(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * What the key audit of the boot found, as the report tells it.
     *
     * @param unused  the keys a {@code VIDOCQ-CFG-003} anomaly names
     * @param audited the namespaces the audit covers, such as {@code vidocq.http.*}, or a key alone
     */
    record Audit(Set<String> unused, List<String> audited) {

        static Audit of(StartupReportView view) {
            Set<String> unused = new HashSet<>();
            for (ReportAnomaly anomaly : view.anomalies()) {
                String message = anomaly.message();
                int start = message.indexOf(QUOTE_START);
                int end = start < 0 ? -1 : message.indexOf(QUOTE_END, start + QUOTE_START.length());
                if (UNREAD_KEY.equals(anomaly.code()) && end >= 0) {
                    unused.add(message.substring(start + QUOTE_START.length(), end));
                }
            }
            List<String> audited = new ArrayList<>();
            for (ReportSection section : view.sections()) {
                if (!"configuration".equals(section.id())) {
                    continue;
                }
                for (ReportLine line : section.lines()) {
                    if ("audited".equals(line.key())) {
                        audited.addAll(line.values());
                    }
                }
            }
            return new Audit(Set.copyOf(unused), List.copyOf(audited));
        }

        String status(String key) {
            if (unused.contains(key)) {
                return UNUSED;
            }
            for (String namespace : audited) {
                boolean claimed = namespace.endsWith("*")
                        ? key.startsWith(namespace.substring(0, namespace.length() - 1))
                        : key.equals(namespace);
                if (claimed) {
                    return CLAIMED;
                }
            }
            return NOT_AUDITED;
        }
    }
}
