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
package io.vidocq.runtime.extensions.essentials.migration.dev;

import io.vidocq.runtime.extensions.essentials.migration.MigrationInfo;
import io.vidocq.runtime.extensions.essentials.migration.live.MigrationControl;
import io.vidocq.runtime.extensions.essentials.migration.live.MigrationControl.LiveOutcome;
import io.vidocq.runtime.extensions.essentials.migration.live.MigrationLive;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.util.List;

/**
 * The migration section, live: one group per migrated datasource, from {@link MigrationLive}, the control the
 * runtime extension publishes once a migrator is selected and clears first thing in its {@code onStop}. No
 * migration runs here; this panel only reads what the extension already holds, and offers the {@code migrate} and
 * {@code clean-and-migrate} actions over that same control, which keeps the operations and their locks.
 */
public final class MigrationLivePanel implements LivePanel {

    /** The argument both actions take: the name of a datasource that is migrated. */
    private static final String DATASOURCE = "datasource";
    private static final String CLEAN_CONFIRMATION = "Drop every object in the schema of the chosen datasource, then"
            + " migrate it again? This cannot be undone.";
    /** The columns of the {@code applied} and {@code pending} tables of the panel. */
    private static final List<String> COLUMNS = List.of("Version", "Description", "Type", "Installed on", "State");
    /** The rows a table of the console keeps: the most recent applied migrations, the first pending ones. */
    private static final int MAX_ROWS = 100;

    /** Created by the service loader. */
    public MigrationLivePanel() {}

    @Override
    public String id() {
        return "migration";
    }

    /**
     * Per datasource, a group: its version, what the last run did, whether its schema may be cleaned, and the
     * {@code applied} and {@code pending} tables when they were listed. Reads {@link MigrationLive}, nothing else;
     * writes no group at all once the runtime extension has cleared it.
     */
    @Override
    public void sample(PanelSample sample) {
        MigrationControl control = MigrationLive.control();
        if (control == null) {
            return;
        }
        for (LiveOutcome o : control.outcomes()) {
            PanelSample group = sample.group(o.dataSource())
                    .text("version", o.version())
                    .text("last-run", o.lastRun())
                    .text("clean", o.cleanDisabled()
                            ? "disabled: " + o.cleanDisabledKey() + "=false allows it"
                            : "allowed");
            MigrationInfo info = o.info();
            if (info == null) {
                group.absent("applied", o.infoAbsent()).absent("pending", o.infoAbsent());
            } else {
                List<MigrationInfo.Migration> applied = info.applied();
                group.table("applied", COLUMNS,
                                rows(applied.subList(Math.max(0, applied.size() - MAX_ROWS), applied.size())))
                        .table("pending", COLUMNS, rows(info.pending().subList(0,
                                Math.min(MAX_ROWS, info.pending().size()))));
            }
        }
    }

    /**
     * {@code migrate} and {@code clean-and-migrate}, each taking one of the migrated datasources by name; none when
     * there is no control published, or it migrates nothing. The console calls it in a dev launch only.
     */
    @Override
    public List<PanelAction> actions() {
        MigrationControl control = MigrationLive.control();
        if (control == null) {
            return List.of();
        }
        List<String> names = control.dataSources();
        if (names.isEmpty()) {
            return List.of();
        }
        String[] values = names.toArray(String[]::new);
        return List.of(
                new PanelAction("migrate", "Migrate now", null,
                        List.of(PanelAction.Argument.oneOf(DATASOURCE, "Datasource", values)),
                        arguments -> control.migrateNow(arguments.get(DATASOURCE))),
                new PanelAction("clean-and-migrate", "Clean and migrate", CLEAN_CONFIRMATION,
                        List.of(PanelAction.Argument.oneOf(DATASOURCE, "Datasource", values)),
                        arguments -> control.cleanAndMigrate(arguments.get(DATASOURCE))));
    }

    private static List<List<String>> rows(List<MigrationInfo.Migration> migrations) {
        return migrations.stream()
                .map(m -> List.of(m.version(), m.description(), m.type(), m.installedOn(), m.state()))
                .toList();
    }
}
