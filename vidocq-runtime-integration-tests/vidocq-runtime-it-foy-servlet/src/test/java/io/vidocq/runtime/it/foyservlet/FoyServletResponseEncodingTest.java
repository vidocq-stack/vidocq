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
package io.vidocq.runtime.it.foyservlet;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default response encoding of the application under the runtime: UTF-8 when the application
 * configures nothing, the {@code web.xml} {@code <response-character-encoding>} when it declares one,
 * and the value of {@code vidocq.servlet.response-character-encoding} otherwise. The servlet writes
 * through {@code getWriter()} without naming an encoding.
 */
class FoyServletResponseEncodingTest {

    private static final String ENCODING_KEY = "vidocq.servlet.response-character-encoding";
    private static final List<String> KEYS = List.of("vidocq.chappe.listener.default.host",
            "vidocq.chappe.listener.default.port", ENCODING_KEY);

    /** A descriptor that only sets the response encoding: the annotated servlets still apply. */
    private static final String ISO_8859_1_WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <response-character-encoding>ISO-8859-1</response-character-encoding>
            </web-app>
            """;

    private VidocqBootstrap bootstrap;
    private ClassLoader previousLoader;
    private URLClassLoader descriptorLoader;

    @AfterEach
    void shutdown() throws Exception {
        if (bootstrap != null) bootstrap.shutdown();
        if (descriptorLoader != null) {
            Thread.currentThread().setContextClassLoader(previousLoader);
            descriptorLoader.close();
        }
        KEYS.forEach(System::clearProperty);
    }

    /** The descriptor of the application (none when null), the key's value (unset when null), the encoding. */
    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("nothing configured: UTF-8", null, null, StandardCharsets.UTF_8),
                Arguments.of("the web.xml encoding is kept", ISO_8859_1_WEB_XML, null, StandardCharsets.ISO_8859_1),
                Arguments.of("the key restores the Servlet default", null, "ISO-8859-1", StandardCharsets.ISO_8859_1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aWriterResponseTakesTheContextsDefaultEncoding(String description, String webXml, String key,
                                                        Charset expected, @TempDir Path root) throws Exception {
        if (key != null) System.setProperty(ENCODING_KEY, key);
        if (webXml != null) withDescriptor(root, webXml);
        String base = boot();

        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/greeting")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, r.statusCode(), description);
        assertEquals("text/plain;charset=" + expected.name(),
                r.headers().firstValue("Content-Type").orElse("").replace(" ", ""), description);
        assertArrayEquals(GreetingServlet.TEXT.getBytes(expected), r.body(), description);
    }

    /**
     * Foy reads {@code WEB-INF/web.xml} through the loader the extension hands it, the thread context
     * class loader at boot: a child of it that also holds {@code root} brings the descriptor.
     */
    private void withDescriptor(Path root, String webXml) throws Exception {
        Files.createDirectories(root.resolve("WEB-INF"));
        Files.writeString(root.resolve("WEB-INF/web.xml"), webXml);
        previousLoader = Thread.currentThread().getContextClassLoader();
        descriptorLoader = new URLClassLoader(new URL[]{root.toUri().toURL()}, previousLoader);
        Thread.currentThread().setContextClassLoader(descriptorLoader);
    }

    private String boot() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", Integer.toString(port));
        bootstrap = VidocqBootstrap.create().configure().start();
        return "http://127.0.0.1:" + port;
    }
}
