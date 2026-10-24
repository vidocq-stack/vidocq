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

    @Test
    void theUrlIsGivenAsAUriOrAString(@VidocqUrl URI uri, @VidocqUrl String text, VidocqApplication application) {
        assertEquals(application.url(), uri);
        assertEquals(uri.toString(), text);
        assertTrue(text.matches("http://127\\.0\\.0\\.1:\\d+"), text);
    }
}
