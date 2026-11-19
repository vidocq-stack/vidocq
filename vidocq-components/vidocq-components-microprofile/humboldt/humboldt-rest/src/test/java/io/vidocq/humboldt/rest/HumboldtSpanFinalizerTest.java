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
package io.vidocq.humboldt.rest;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapper answers an exception no application mapper handles as the runtime would without it: a bare 500 that
 * tells the client nothing of the failure (CWE-209), and an ERROR line that keeps it for the operator
 * (BUG-20261114-01).
 */
class HumboldtSpanFinalizerTest {

    private record Entry(System.Logger.Level level, String message, Throwable thrown) { }

    private final List<Entry> logged = new ArrayList<>();

    private Response map(Throwable exception) {
        System.Logger recording = new System.Logger() {
            @Override public String getName() { return "recording"; }
            @Override public boolean isLoggable(System.Logger.Level level) { return true; }
            @Override public void log(System.Logger.Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                logged.add(new Entry(level, msg, thrown));
            }
            @Override public void log(System.Logger.Level level, ResourceBundle bundle, String format, Object... params) {
                logged.add(new Entry(level, MessageFormat.format(format, params), null));
            }
        };
        System.Logger previous = HumboldtSpanFinalizer.useLogger(recording);
        try {
            return new HumboldtSpanFinalizer().toResponse(exception);
        } finally {
            HumboldtSpanFinalizer.useLogger(previous);
        }
    }

    @Test
    void anUnmappedExceptionAnswersABare500WithoutItsMessage() {
        Response response = map(new IllegalStateException("secret detail 42"));

        assertEquals(500, response.getStatus());
        assertFalse(response.hasEntity(), () -> "entity: " + response.getEntity());
        assertEquals(null, response.getMediaType());
    }

    @Test
    void anUnmappedExceptionIsLoggedOnceAtErrorWithItsCause() {
        IllegalStateException failure = new IllegalStateException("secret detail 42");

        map(failure);

        assertEquals(1, logged.size(), logged::toString);
        assertEquals(System.Logger.Level.ERROR, logged.getFirst().level());
        assertSame(failure, logged.getFirst().thrown());
    }

    @Test
    void aWebApplicationExceptionKeepsItsOwnResponseAndIsNotLogged() {
        NotFoundException notFound = new NotFoundException();

        Response response = map(notFound);

        assertSame(notFound.getResponse(), response);
        assertTrue(logged.isEmpty(), logged::toString);
    }
}
