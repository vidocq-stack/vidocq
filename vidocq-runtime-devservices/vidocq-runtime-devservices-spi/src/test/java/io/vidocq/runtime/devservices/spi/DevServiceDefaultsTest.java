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
package io.vidocq.runtime.devservices.spi;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The SPI's {@code default} methods (spec 2026-09-29-devservice-postgres-kind §3, §4): an implementation written
 * before them still compiles, and answers "unknown".
 */
class DevServiceDefaultsTest {

    /** A context that implements only what the SPI asked for before. */
    private static final DevServiceContext OLD_CONTEXT = new DevServiceContext() {
        @Override public Optional<String> property(String key) { return Optional.of("explicit"); }
        @Override public Map<String, String> properties() { return Map.of(); }
        @Override public Path basedir() { return Path.of("."); }
        @Override public Path resolve(String relative) { return Path.of(relative); }
        @Override public System.Logger log() { return System.getLogger("test"); }
    };

    @Test
    void anOlderContextKnowsNoApplicationValueAndNoClass() {
        assertEquals(Optional.empty(), OLD_CONTEXT.applicationProperty("vidocq.pool.url"));
        assertFalse(OLD_CONTEXT.onApplicationClasspath("org.postgresql.Driver"));
    }

    @Test
    void aProviderGivesNoReasonUnlessItSaysOne() {
        DevService provider = new DevService() {
            @Override public String id() { return "old"; }
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public Map<String, String> start(DevServiceContext ctx) { return Map.of(); }
            @Override public void stop() { }
        };

        assertNull(provider.skipReason(OLD_CONTEXT));
    }
}
