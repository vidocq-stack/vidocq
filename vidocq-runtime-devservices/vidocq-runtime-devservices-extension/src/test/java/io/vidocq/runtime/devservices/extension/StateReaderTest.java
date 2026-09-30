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
package io.vidocq.runtime.devservices.extension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateReaderTest {

    /**
     * A real {@code StateFile.json(...)} output (Task 4), generated with a one-off program against the installed
     * {@code vidocq-runtime-devservices-host} classes, for the scenario the brief describes: a single postgres
     * service, image {@code postgres:16-alpine}, one endpoint ({@code default} → {@code localhost:54321}), and the
     * three keys a single datasource injects ({@code vidocq.pool.url}, {@code vidocq.pool.username},
     * {@code vidocq.pool.password}). This differs from the brief's own text block, which only listed the url and
     * password keys: the host also injects {@code vidocq.pool.username}, and {@code StateFile} sorts injected keys
     * alphabetically (password, url, username), so the real output carries all three, in that order.
     */
    static final String JSON = """
        {"host":"vidocq:dev","state":"running","startedAt":"2026-09-24T10:12:03Z","services":[{"id":"postgres",\
        "image":"postgres:16-alpine","endpoints":{"default":"localhost:54321"},"injected":[\
        {"key":"vidocq.pool.password","configured":true},\
        {"key":"vidocq.pool.url","value":"jdbc:postgresql://localhost:54321/vidocq"},\
        {"key":"vidocq.pool.username","value":"vidocq"}]}]}""";

    @Test
    void parsesTheHostsFile() {
        DevServicesSnapshot s = StateReader.parse(JSON);
        assertEquals("vidocq:dev", s.host());
        assertEquals("running", s.state());
        assertEquals(1, s.services().size());
        DevServicesSnapshot.Service pg = s.services().getFirst();
        assertEquals("postgres:16-alpine", pg.image());
        assertEquals(Map.of("default", "localhost:54321"), pg.endpoints());
        assertEquals(new DevServicesSnapshot.Injected("vidocq.pool.password", null, true), pg.injected().getFirst());
    }

    /** Review Focus: a file written before "skipped" existed, and hand-edited entries, read without a surprise. */
    @Test
    void readsTheSkippedProvidersAndAnOlderFileHasNone() {
        DevServicesSnapshot s = StateReader.parse("""
            {"host":"vidocq:dev","state":"running","startedAt":"x","services":[],"skipped":[\
            {"id":"postgres","reason":"vidocq.pool.url is jdbc:h2, not PostgreSQL"},{"id":"acme","reason":null},\
            {"reason":"an entry without an id is dropped"},{"id":" ","reason":"so is a blank one"}]}""");

        assertEquals(List.of(
                new DevServicesSnapshot.Skipped("postgres", "vidocq.pool.url is jdbc:h2, not PostgreSQL"),
                new DevServicesSnapshot.Skipped("acme", null)), s.skipped());
        assertEquals(List.of(), StateReader.parse(JSON).skipped(), "a file written before skipped existed");
        assertEquals(List.of(), DevServicesSnapshot.NONE.skipped());
    }

    /** #166: an entry of "skipped" that is no object is dropped, and the services are still read. */
    @Test
    void aSkippedEntryThatIsNoObjectIsDroppedAndTheServicesStillRead() {
        DevServicesSnapshot s = StateReader.parse("""
            {"host":"vidocq:dev","state":"running","startedAt":"x","services":[{"id":"postgres","image":"pg",\
            "endpoints":{},"injected":[]}],"skipped":[null,"x",3,{"id":"keycloak","reason":"r"}]}""");

        assertEquals(1, s.services().size());
        assertEquals(List.of(new DevServicesSnapshot.Skipped("keycloak", "r")), s.skipped());
    }

    @Test
    void rejectsWhatItCannotRead() {
        assertThrows(IllegalArgumentException.class, () -> StateReader.parse("{\"host\":"));
        assertThrows(IllegalArgumentException.class, () -> StateReader.parse("[]"));
    }

    @Test
    void readsEscapes() {
        DevServicesSnapshot s = StateReader.parse(
                "{\"host\":\"a\\\"b\\n\",\"state\":\"running\",\"startedAt\":\"x\",\"services\":[]}");
        assertEquals("a\"b\n", s.host());
    }
}
