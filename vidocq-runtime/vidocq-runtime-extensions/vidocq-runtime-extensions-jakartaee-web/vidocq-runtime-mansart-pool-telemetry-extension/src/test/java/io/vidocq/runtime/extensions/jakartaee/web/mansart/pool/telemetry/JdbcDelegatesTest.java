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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The committed {@code Delegating*} classes are what {@link JdbcDelegatesGenerator} writes from this JDK's
 * {@code java.sql} interfaces; {@code -Dvidocq.jdbc.delegates.write=true} rewrites them.
 */
class JdbcDelegatesTest {

    private static final Path SOURCES = Path.of("src", "main", "java",
            JdbcDelegatesGenerator.PACKAGE.replace('.', '/'));

    @Test
    void theCommittedDelegatesAreTheGeneratedOnes() throws IOException {
        String licence = licence(Files.readString(SOURCES.resolve("JdbcExecution.java")));
        Map<String, String> generated = new JdbcDelegatesGenerator(licence).generate();
        boolean write = Boolean.getBoolean("vidocq.jdbc.delegates.write");
        for (var entry : generated.entrySet()) {
            Path file = SOURCES.resolve(entry.getKey() + ".java");
            if (write) {
                Files.writeString(file, entry.getValue());
            }
            assertEquals(entry.getValue(), Files.exists(file) ? Files.readString(file) : "",
                    file + " differs from what JdbcDelegatesGenerator writes; regenerate it with"
                            + " -Dvidocq.jdbc.delegates.write=true");
        }
    }

    /** The licence header of a hand-written source, so the generated ones carry the same. */
    private static String licence(String source) {
        return source.substring(0, source.indexOf(" */\n") + 4) + "\n";
    }
}
