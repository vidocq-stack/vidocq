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

package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Reader;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalResourcesTest {

    @Test
    void charsetPrecedence() {
        assertEquals(StandardCharsets.UTF_8, ExternalResources.charsetOf("UTF-8", "text/html; charset=iso-8859-1"));
        assertEquals(StandardCharsets.ISO_8859_1, ExternalResources.charsetOf(null, "text/html; charset=\"ISO-8859-1\";x=y"));
        assertEquals(StandardCharsets.ISO_8859_1, ExternalResources.charsetOf(null, "text/html"));
        assertEquals(StandardCharsets.ISO_8859_1, ExternalResources.charsetOf(null, null));
        assertEquals(StandardCharsets.UTF_8, ExternalResources.charsetOf(null, "text/plain;Charset=utf-8"));
    }

    @Test
    void httpRequestsHaveATimeout() {
        assertEquals(Optional.of(Duration.ofSeconds(30)),
                JdkExternalResources.httpRequest(URI.create("http://example.org/x")).timeout());
    }

    @Test
    void urlConnectionsHaveConnectAndReadTimeouts(@TempDir Path dir) throws Exception {
        URLConnection connection = dir.toUri().toURL().openConnection();
        JdkExternalResources.withTimeouts(connection);
        assertEquals(10_000, connection.getConnectTimeout());
        assertEquals(30_000, connection.getReadTimeout());
    }

    @Test
    void jdkReadsNonHttpSchemes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "héllo", StandardCharsets.UTF_8);
        try (Reader reader = ExternalResources.jdk().open(file.toUri().toString(), "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                sb.append((char) c);
            }
            assertEquals("héllo", sb.toString());
        }
    }
}
