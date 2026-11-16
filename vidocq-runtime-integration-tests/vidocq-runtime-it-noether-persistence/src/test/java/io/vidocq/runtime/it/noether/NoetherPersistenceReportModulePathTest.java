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
package io.vidocq.runtime.it.noether;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The {@code noether-persistence} section of the startup report, read from the report this boot logs, at the
 * detailed level: its summary counts the injected units and their entities, and a row gives each unit's boot facts.
 */
class NoetherPersistenceReportModulePathTest {

    private static final String REPORT_LEVEL = "vidocq.startup.report";
    private static final List<String> LOGGED = new ArrayList<>();

    private static VidocqBootstrap bootstrap;

    @BeforeAll
    static void bootAndReadTheReport() throws Exception {
        Logger startup = Logger.getLogger("io.vidocq.startup");
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord logged) {
                synchronized (LOGGED) {
                    LOGGED.add(logged.getMessage());
                }
            }

            @Override
            public void flush() {
                // kept in memory
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        String level = System.getProperty(REPORT_LEVEL);
        System.setProperty(REPORT_LEVEL, "detailed");
        startup.addHandler(capture);
        try {
            bootstrap = VidocqBootstrap.create().configure().start();
        } finally {
            startup.removeHandler(capture);
            if (level == null) {
                System.clearProperty(REPORT_LEVEL);
            } else {
                System.setProperty(REPORT_LEVEL, level);
            }
        }
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    private static String report() {
        synchronized (LOGGED) {
            return String.join("\n", LOGGED);
        }
    }

    @Test
    void the_summary_counts_the_injected_units_and_their_entities() {
        assertTrue(report().contains("3 persistence units, 3 entities"), report());
    }

    @Test
    void a_row_gives_the_pool_and_the_schema_action_of_each_unit() {
        assertTrue(report().contains("JTA on pool notes, schema action drop-and-create, 1 entity"), report());
        assertTrue(report().contains("JTA on the default pool, schema action drop-and-create, 1 entity"), report());
    }
}
