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
package io.vidocq.runtime.spi.report;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The records of {@link StartupReportView}: what a reader of the report can count on. */
class ReportViewRecordsTest {

    @Test
    void anAnomalyHasACodeAMessageAndASourceAndMayHaveNoHint() {
        ReportAnomaly anomaly = new ReportAnomaly("VIDOCQ-CFG-003", "unknown key vidocq.foo", null, "core");

        assertNull(anomaly.hint());
        assertThrows(NullPointerException.class, () -> new ReportAnomaly(null, "message", "hint", "core"));
        assertThrows(NullPointerException.class, () -> new ReportAnomaly("VIDOCQ-CFG-003", null, "hint", "core"));
        assertThrows(NullPointerException.class, () -> new ReportAnomaly("VIDOCQ-CFG-003", "message", "hint", null));
    }

    @Test
    void aSectionHasAnIdAndMayHaveNothingElse() {
        ReportSection section = new ReportSection("configuration", null, null, null);

        assertNull(section.headline());
        assertNull(section.summary());
        assertEquals(List.of(), section.lines());
        assertThrows(NullPointerException.class, () -> new ReportSection(null, "headline", "summary", List.of()));
    }

    @Test
    void aSectionKeepsItsOwnCopyOfItsLines() {
        ReportLine weaving = new ReportLine("weaving", List.of("none"));
        List<ReportLine> lines = new ArrayList<>(List.of(weaving));
        ReportSection section = new ReportSection("layer", "4 modules", "4 modules", lines);

        lines.clear();

        assertEquals(List.of(weaving), section.lines());
        assertThrows(UnsupportedOperationException.class, () -> section.lines().add(weaving));
    }

    @Test
    void aTableRowAndALineOfTextHaveNoKey() {
        ReportLine tableRow = new ReportLine(null, List.of("com.acme.app", "app/target/classes", "directory"));
        ReportLine text = new ReportLine(null, List.of("no application layer"));

        assertNull(tableRow.key());
        assertEquals(List.of("com.acme.app", "app/target/classes", "directory"), tableRow.values());
        assertNull(text.key());
        assertEquals(List.of("no application layer"), text.values());
    }

    @Test
    void aLineKeepsItsOwnCopyOfItsValuesAndReadsANullValueAsTheReportPrintsIt() {
        List<String> values = new ArrayList<>(Arrays.asList("h2", null));
        ReportLine line = new ReportLine("databases", values);

        values.clear();

        assertEquals(List.of("h2", "null"), line.values());
        assertThrows(UnsupportedOperationException.class, () -> line.values().add("postgresql"));
        assertEquals(List.of(), new ReportLine("databases", null).values());
    }
}
