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
package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Origins of the {@code mp.jwt.verify.publickey.location} document (MicroProfile JWT 2.2 §9.2.2):
 * HTTP(S), {@code file:} URI, class path resource, then plain file system path.
 */
class JwksSourceTest {

    private static final String DOC = "{\"keys\":[]}";
    private static final byte[] DOC_BYTES = DOC.getBytes(StandardCharsets.UTF_8);
    private static final String JWKS_JSON = "jwks.json";
    private static final String JWKS_PATH = "/jwks";

    @TempDir
    Path dir;

    @Test
    void httpLocation_fetchesTheBody() throws Exception {
        try (TestHttpServer server = new TestHttpServer(200, DOC)) {
            JwksSource source = JwksSource.fromLocation(server.url(JWKS_PATH));
            assertInstanceOf(JwksSource.Http.class, source);
            assertArrayEquals(DOC_BYTES, source.fetch());
        }
    }

    @Test
    void httpErrorStatus_isRejected() throws Exception {
        try (TestHttpServer server = new TestHttpServer(404, "missing")) {
            JwksSource source = JwksSource.fromLocation(server.url(JWKS_PATH));
            JwtValidationException ex = assertThrows(JwtValidationException.class, source::fetch);
            assertTrue(ex.getMessage().contains("HTTP 404"), ex.getMessage());
        }
    }

    @Test
    void unreachableHttpServer_isRejected() throws Exception {
        String url;
        try (TestHttpServer server = new TestHttpServer(200, DOC)) {
            url = server.url(JWKS_PATH);
        }
        JwksSource source = JwksSource.fromLocation(url);
        JwtValidationException ex = assertThrows(JwtValidationException.class, source::fetch);
        assertTrue(ex.getMessage().contains("fetch failed"), ex.getMessage());
    }

    @Test
    void fileUri_readsTheFile() throws Exception {
        Path file = Files.write(dir.resolve(JWKS_JSON), DOC_BYTES);
        JwksSource source = JwksSource.fromLocation(file.toUri().toString());
        assertInstanceOf(JwksSource.File.class, source);
        assertArrayEquals(DOC_BYTES, source.fetch());
    }

    @Test
    void missingFile_isRejected() throws Exception {
        JwksSource source = JwksSource.fromLocation(dir.resolve("absent.json").toUri().toString());
        JwtValidationException ex = assertThrows(JwtValidationException.class, source::fetch);
        assertTrue(ex.getMessage().contains("unreadable"), ex.getMessage());
    }

    @Test
    void plainPathOutsideTheClassPath_fallsBackToTheFileSystem() throws Exception {
        Path file = Files.write(dir.resolve(JWKS_JSON), DOC_BYTES);
        assertArrayEquals(DOC_BYTES, JwksSource.fromLocation(file.toString()).fetch());
    }

    @Test
    void resourceVisibleOnlyToTheContextClassLoader_isFound() throws Exception {
        Files.write(dir.resolve("tccl-only-jwks.json"), DOC_BYTES);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            thread.setContextClassLoader(loader);
            assertArrayEquals(DOC_BYTES, JwksSource.fromLocation("/tccl-only-jwks.json").fetch());
            assertArrayEquals(DOC_BYTES, JwksSource.fromLocation("tccl-only-jwks.json").fetch());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void classPathResource_isRead() throws Exception {
        byte[] bytes = JwksSource.fromLocation("/cervantes-test-jwks.json").fetch();
        assertEquals(DOC, new String(bytes, StandardCharsets.UTF_8).trim());
    }

    @Test
    void cleartextHttpToANonLoopbackHost_isRefused() {
        // Security audit F-05: cleartext http:// to a remote host lets a MITM serve attacker keys.
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> JwksSource.fromLocation("http://192.0.2.1/jwks"));
        assertTrue(ex.getMessage().contains("https"), ex.getMessage());
    }

    @Test
    void cleartextHttpToANonLoopbackHost_isAllowedWhenExplicitlyOptedIn() throws Exception {
        System.setProperty("io.vidocq.cervantes.jwks.allowHttp", "true");
        try {
            assertInstanceOf(JwksSource.Http.class, JwksSource.fromLocation("http://192.0.2.1/jwks"));
        } finally {
            System.clearProperty("io.vidocq.cervantes.jwks.allowHttp");
        }
    }

    @Test
    void cleartextHttpToLoopback_isAllowedForLocalDevelopment() throws Exception {
        try (TestHttpServer server = new TestHttpServer(200, DOC)) {
            assertArrayEquals(DOC_BYTES, JwksSource.fromLocation(server.url(JWKS_PATH)).fetch());
        }
    }

    @Test
    void anOverSizedJwksBody_isRejected() throws Exception {
        String big = "x".repeat(2 * 1024 * 1024); // 2 MiB > the 1 MiB cap
        try (TestHttpServer server = new TestHttpServer(200, big)) {
            JwksSource source = JwksSource.fromLocation(server.url(JWKS_PATH));
            JwtValidationException ex = assertThrows(JwtValidationException.class, source::fetch);
            assertTrue(ex.getMessage().contains("1 MiB"), ex.getMessage());
        }
    }
}
