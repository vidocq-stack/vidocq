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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.UnitDescription.ForeignKey;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Index;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Table;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.IndexDiff.Found;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The indexes a unit declares against those the database has: same, missing, extra or different. */
class IndexDiffTest {

    static final Table NOTE = new Table("NOTE", List.of(), List.of("ID"), List.of(
            new Index("idx_note_title", List.of("TITLE"), false),
            new Index("uk_note_code", List.of("CODE"), true),
            new Index("idx_note_day", List.of("DAY"), false),
            new Index("idx_note_pair", List.of("TITLE"), false),
            new Index(null, List.of("SLUG"), true)),
            List.of(new ForeignKey("fk_note_author", List.of("AUTHOR_ID"), "AUTHOR", List.of("ID"))));

    @Test
    void eachDeclaredIndexIsSameMissingOrDifferentAndEachOtherFoundIsExtra() {
        List<Found> found = List.of(
                new Found("NOTE", "IDX_NOTE_TITLE", List.of("TITLE"), false),
                new Found("NOTE", "UK_NOTE_CODE", List.of("CODE"), false),
                new Found("NOTE", "IDX_NOTE_PAIR", List.of("TITLE", "DAY"), false),
                new Found("NOTE", "CONSTRAINT_INDEX_2", List.of("SLUG"), true),
                new Found("NOTE", "IDX_STRAY", List.of("DAY", "TITLE"), false));

        assertEquals(List.of(
                List.of("NOTE", "idx_note_title", "(TITLE)", "(TITLE)", IndexDiff.SAME),
                List.of("NOTE", "uk_note_code", "unique (CODE)", "(CODE)", IndexDiff.DIFFERENT),
                List.of("NOTE", "idx_note_day", "(DAY)", "", IndexDiff.MISSING),
                List.of("NOTE", "idx_note_pair", "(TITLE)", "(TITLE, DAY)", IndexDiff.DIFFERENT),
                List.of("NOTE", "CONSTRAINT_INDEX_2", "unique (SLUG)", "unique (SLUG)", IndexDiff.SAME),
                List.of("NOTE", "IDX_STRAY", "", "(DAY, TITLE)", IndexDiff.EXTRA)),
                IndexDiff.rows(List.of(NOTE), found));
    }

    @Test
    void anIndexInAnotherCaseIsTheSame() {
        Table lower = new Table("note", List.of(), List.of("id"), List.of(
                new Index("idx_note_title", List.of("title"), false)), List.of());

        assertEquals(List.of(List.of("note", "idx_note_title", "(title)", "(TITLE)", IndexDiff.SAME)),
                IndexDiff.rows(List.of(lower), List.of(new Found("NOTE", "IDX_NOTE_TITLE", List.of("TITLE"),
                        false))));
    }

    @Test
    void theIndexesOfThePrimaryKeyAndOfAForeignKeyAreLeftOut() {
        List<Found> found = List.of(
                new Found("NOTE", "PRIMARY_KEY_2", List.of("ID"), true),
                new Found("NOTE", "FK_NOTE_AUTHOR_INDEX_2", List.of("AUTHOR_ID"), false));
        Table bare = new Table("NOTE", List.of(), List.of("ID"), List.of(), NOTE.foreignKeys());

        assertEquals(List.of(), IndexDiff.rows(List.of(bare), found));
    }

    @Test
    void theSummaryCountsEachStatus() {
        List<List<String>> rows = List.of(
                List.of("NOTE", "a", "(A)", "(A)", IndexDiff.SAME),
                List.of("NOTE", "b", "(B)", "(B)", IndexDiff.SAME),
                List.of("NOTE", "c", "(C)", "", IndexDiff.MISSING));

        assertEquals("2 same, 1 missing in the database", IndexDiff.summary(rows));
        assertEquals("no index declared or found", IndexDiff.summary(List.of()));
    }
}
