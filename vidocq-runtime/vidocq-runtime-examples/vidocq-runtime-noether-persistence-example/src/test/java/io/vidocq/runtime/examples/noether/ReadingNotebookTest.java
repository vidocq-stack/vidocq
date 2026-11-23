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
package io.vidocq.runtime.examples.noether;

import io.vidocq.runtime.junit.VidocqApplication;
import io.vidocq.runtime.junit.VidocqResponse;
import io.vidocq.runtime.junit.VidocqTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reading notebook, started by {@code @VidocqTest} as {@code java -m} starts it: its persistence unit runs on
 * Noether, over the default Mansart pool, and its REST API writes and reads through the unit.
 *
 * <p>The tests share one application, so each one uses its own ISBNs and author names.
 */
@VidocqTest
class ReadingNotebookTest {

    private static final String JSON = "application/json";

    @Test
    void theStartupReportHasTheSectionOfTheUnit(VidocqApplication application) {
        assertTrue(application.log().contains("1 persistence unit, 3 entities"), application.log());
    }

    @Test
    void aBookAndItsNotesAreReadBack(VidocqApplication application) {
        long book = catalogue(application, "The Name of the Rose", "978-0-15-144647-6", "Umberto Eco");

        VidocqResponse noted = application.post("/api/books/" + book + "/notes", JSON,
                "{\"text\":\"The library is the labyrinth.\"}");
        assertEquals(201, noted.status(), noted.body() + "\n" + application.log());

        VidocqResponse books = application.get("/api/books");
        assertEquals(200, books.status(), books.body());
        assertTrue(books.body().contains("\"title\":\"The Name of the Rose\""), books.body());
        assertTrue(books.body().contains("\"author\":\"Umberto Eco\""), books.body());

        VidocqResponse notes = application.get("/api/books/" + book + "/notes");
        assertEquals(200, notes.status(), notes.body());
        assertTrue(notes.body().contains("\"text\":\"The library is the labyrinth.\""), notes.body());
    }

    @Test
    void anAuthorIsKeptOnceForAllTheirBooks(VidocqApplication application) {
        catalogue(application, "Invisible Cities", "978-0-15-645380-6", "Italo Calvino");
        catalogue(application, "If on a Winter's Night a Traveler", "978-0-15-643961-9", "Italo Calvino");

        VidocqResponse books = application.get("/api/authors/Italo Calvino/books".replace(" ", "%20"));

        assertEquals(200, books.status(), books.body() + "\n" + application.log());
        assertTrue(books.body().contains("\"title\":\"If on a Winter's Night a Traveler\""), books.body());
        assertTrue(books.body().contains("\"title\":\"Invisible Cities\""), books.body());
        assertTrue(books.body().indexOf("If on a Winter") < books.body().indexOf("Invisible Cities"),
                "the named query sorts by title: " + books.body());
    }

    /**
     * Cataloguing a book creates its author first, then finds that the ISBN is already taken and throws: the
     * transaction rolls back, and the author written in it is gone with it.
     */
    @Test
    void aRefusedBookLeavesNoAuthorBehind(VidocqApplication application) {
        catalogue(application, "Foucault's Pendulum", "978-0-15-132765-2", "Umberto Eco");

        VidocqResponse refused = application.post("/api/books", JSON,
                "{\"title\":\"A Pendulum\",\"isbn\":\"978-0-15-132765-2\",\"author\":\"Nobody Yet\"}");
        assertEquals(409, refused.status(), refused.body() + "\n" + application.log());

        VidocqResponse authors = application.get("/api/authors");
        assertEquals(200, authors.status(), authors.body());
        assertTrue(authors.body().contains("\"name\":\"Umberto Eco\""), authors.body());
        assertFalse(authors.body().contains("Nobody Yet"), authors.body());
    }

    @Test
    void aNoteOnABookThatDoesNotExistIsNotFound(VidocqApplication application) {
        VidocqResponse noted = application.post("/api/books/999999/notes", JSON, "{\"text\":\"lost\"}");

        assertEquals(404, noted.status(), noted.body());
    }

    private static long catalogue(VidocqApplication application, String title, String isbn, String author) {
        VidocqResponse created = application.post("/api/books", JSON,
                "{\"title\":\"" + title + "\",\"isbn\":\"" + isbn + "\",\"author\":\"" + author + "\"}");
        assertEquals(201, created.status(), created.body() + "\n" + application.log());
        Matcher id = Pattern.compile("\"id\":(\\d+)").matcher(created.body());
        assertTrue(id.find(), created.body());
        return Long.parseLong(id.group(1));
    }
}
