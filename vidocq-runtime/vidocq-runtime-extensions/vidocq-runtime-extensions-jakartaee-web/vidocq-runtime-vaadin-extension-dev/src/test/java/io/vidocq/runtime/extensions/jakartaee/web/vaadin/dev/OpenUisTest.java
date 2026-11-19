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
package io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenUisTest {

    private final OpenUis uis = new OpenUis();
    private final UI ui = new UI();

    /** At a swap that keeps the HTTP sessions, the servlet container writes them out with their UIs. */
    @Test
    void anOpenUiCanBeWrittenWithItsSession() throws IOException {
        uis.opened(ui);

        try (var out = new ObjectOutputStream(OutputStream.nullOutputStream())) {
            assertDoesNotThrow(() -> out.writeObject(ui));
        }
    }

    /**
     * A page a swap restored never ran {@code uiInit} in this layer: its UIs open when the page asks for the event
     * stream, its first request to the new deployment.
     */
    @Test
    void theUisOfARestoredSessionOpenWhenItsPageAsksForTheStream() {
        uis.serving(new RestoredSessions.Service(new RestoredSessions.Session(ui)));

        uis.restored(RestoredSessions.request(Map.of(RestoredSessions.ATTRIBUTE, "restored")));

        assertEquals(List.of(ui), uis.all());
    }

    /** Asked for a session the HTTP session does not hold, Vaadin would create one: it is never asked. */
    @Test
    void aPageWithoutAVaadinSessionGetsNone() {
        var service = new RestoredSessions.Service(new RestoredSessions.Session(ui));
        uis.serving(service);

        uis.restored(RestoredSessions.request(Map.of("other", "value")));
        uis.restored(RestoredSessions.request(null));

        assertEquals(0, service.lookups);
        assertEquals(List.of(), uis.all());
    }

    @Test
    void aSessionThatExpiredOpensNothing() {
        var service = new RestoredSessions.Service(null);
        uis.serving(service);

        uis.restored(RestoredSessions.request(Map.of(RestoredSessions.ATTRIBUTE, "expired")));

        assertEquals(1, service.lookups);
        assertEquals(List.of(), uis.all());
    }

    @Test
    void aSessionThatCannotBeReadOpensNothingAndTheStreamGoesOn() {
        var service = new RestoredSessions.Service(null, new IllegalStateException("no lock"));
        uis.serving(service);

        assertDoesNotThrow(() -> uis.restored(RestoredSessions.request(Map.of(RestoredSessions.ATTRIBUTE, "x"))));

        assertEquals(List.of(), uis.all());
    }

    @Test
    void theLookupLeavesNoCurrentSessionOnTheStreamThread() {
        uis.serving(new RestoredSessions.Service(new RestoredSessions.Session(ui)));

        uis.restored(RestoredSessions.request(Map.of(RestoredSessions.ATTRIBUTE, "kept")));

        assertNull(VaadinSession.getCurrent());
    }

    @Test
    void aClosingUiIsNoLongerOpen() {
        uis.opened(ui);

        ui.close();

        assertEquals(List.of(), uis.all());
    }
}
