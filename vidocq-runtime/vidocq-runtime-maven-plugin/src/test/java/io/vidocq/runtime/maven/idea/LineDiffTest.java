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
package io.vidocq.runtime.maven.idea;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the line diff {@code vidocq:idea -Dvidocq.idea.check=true} prints for a run configuration that
 * does not match the Maven projects: two spaces for an unchanged line, {@code "- "} for the line on disk,
 * {@code "+ "} for the expected line.
 */
class LineDiffTest {

    @Test
    void equalTextsProduceOnlyUnchangedLines() {
        assertEquals(List.of("  a", "  b"), LineDiff.diff("a\nb", "a\nb"));
    }

    @Test
    void aChangedLineIsRemovedThenAdded() {
        assertEquals(List.of("  a", "- old", "+ new", "  c"), LineDiff.diff("a\nold\nc", "a\nnew\nc"));
    }

    @Test
    void insertionsAtTheEndAndDeletionsAtTheStartAreReported() {
        assertEquals(List.of("  a", "+ b"), LineDiff.diff("a", "a\nb"));
        assertEquals(List.of("- a", "  b"), LineDiff.diff("a\nb", "b"));
    }

    @Test
    void anEmptyTextOnDiskShowsEveryExpectedLineAsAdded() {
        assertEquals(List.of("+ a", "+ b"), LineDiff.diff("", "a\nb"));
    }
}
