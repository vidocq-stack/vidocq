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
package io.vidocq.runtime.examples.rest;

import io.vidocq.runtime.junit.VidocqApplication;
import io.vidocq.runtime.junit.VidocqResponse;
import io.vidocq.runtime.junit.VidocqTest;
import io.vidocq.runtime.junit.VidocqUrl;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The application, started by {@code @VidocqTest}, answers over HTTP: its REST resources are mounted on /api (Vidocq/vidocq#350). */
@VidocqTest
class TodoResourceTest {

    @Test
    void aCreatedTodoIsListed(VidocqApplication application) {
        VidocqResponse created = application.post("/api/todos", "application/json", "{\"title\":\"write the first test\"}");
        assertEquals(201, created.status(), created.body() + "\n" + application.log());

        VidocqResponse listed = application.get("/api/todos");

        assertEquals(200, listed.status(), listed.body());
        assertTrue(listed.header("Content-Type").orElseThrow().startsWith("application/json"), listed.headers().toString());
        assertTrue(listed.body().contains("\"title\":\"write the first test\""), listed.body());
    }

    /**
     * The card's title goes through a JSON-B adapter that is a CDI bean, its style injected: Champollion obtains it
     * from the container through the registry of {@code champollion-jsonb-cdi}, which two providers can run here,
     * {@code champollion-jsonb-cdi-vauban} and the components {@code vidocq:generate} wrote into the enriched copy of
     * the jar. A registry installed twice, as two registry beans or one Startup observer called twice, makes JSON-B
     * fail ("2 running CDI containers") and the card answer 500.
     */
    @Test
    void aCardTitleGoesThroughAnAdapterTheContainerInjects(VidocqApplication application) {
        VidocqResponse created = application.post("/api/todos", "application/json", "{\"title\":\"water the plants\"}");
        assertEquals(201, created.status(), created.body() + "\n" + application.log());
        Matcher id = Pattern.compile("\"id\":(\\d+)").matcher(created.body());
        assertTrue(id.find(), created.body());

        VidocqResponse card = application.get("/api/todos/" + id.group(1) + "/card");

        assertEquals(200, card.status(), card.body() + "\n" + application.log());
        assertTrue(card.body().contains("\"title\":\"WATER THE PLANTS\""), card.body());
    }

    @Test
    void theUrlIsGivenAsAUriOrAString(@VidocqUrl URI uri, @VidocqUrl String text, VidocqApplication application) {
        assertEquals(application.url(), uri);
        assertEquals(uri.toString(), text);
        assertTrue(text.matches("http://127\\.0\\.0\\.1:\\d+"), text);
    }
}
