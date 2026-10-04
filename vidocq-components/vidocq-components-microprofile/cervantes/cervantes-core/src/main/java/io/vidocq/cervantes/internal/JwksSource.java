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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Source of the bytes of a JWK Set document ({@code mp.jwt.verify.publickey.location}). Abstracts
 * the origin (HTTP or file) for {@link JwksKeyResolver}.
 */
@FunctionalInterface
interface JwksSource {

    /** @return the raw JWKS content; throws if the source is unreachable or unreadable. */
    byte[] fetch() throws JwtValidationException;

    /**
     * Built a source from a rental (MP JWT spec §9.2.2):
     * <ul>
     * <li>{@code http(s)://…} → HTTP (HttpClient JDK, virtual threads)</li>
     * <li>{@code /…} → classpath resource (Thread classloader or system classloader)</li>
     * <li>{@code file:…} → file path with URI</li> schema
     * <li>Sinon → classpath resource (relative path) then system file</li>
     * </ul>
     */
    static JwksSource fromLocation(String location, HttpClient httpClient, Duration timeout) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return new Http(URI.create(location), httpClient, timeout);
        }
        if (location.startsWith("file:")) {
            return new File(Path.of(URI.create(location)));
        }
        // Classpath resource: starts with "/" or relative path
        //MP JWT spec §9.2.2: location is treated as a classpath resource first
        String classpathPath = location.startsWith("/") ? location : "/" + location;
        java.net.URL url = JwksSource.class.getResource(classpathPath);
        if (url == null) {
            url = Thread.currentThread().getContextClassLoader().getResource(
                    location.startsWith("/") ? location.substring(1) : location);
        }
        if (url != null) {
            final java.net.URL finalUrl = url;
            return () -> {
                try {
                    return finalUrl.openStream().readAllBytes();
                } catch (java.io.IOException e) {
                    throw new JwtValidationException("Classpath resource unreadable: " + location, e);
                }
            };
        }
        // Fallback to filesystem
        return new File(Path.of(location));
    }

    /** Variant with a default {@link HttpClient} (5 s connect timeout). */
    static JwksSource fromLocation(String location) {
        return fromLocation(
                location,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(5));
    }

    /** HTTP source: a blocking GET on a virtual thread (no pinning). */
    final class Http implements JwksSource {
        private final URI uri;
        private final HttpClient client;
        private final Duration timeout;

        Http(URI uri, HttpClient client, Duration timeout) {
            this.uri = uri;
            this.client = client;
            this.timeout = timeout;
        }

        @Override
        public byte[] fetch() throws JwtValidationException {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Accept", "application/json, text/plain, */*")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    throw new JwtValidationException("JWKS fetch returned HTTP " + response.statusCode() + " from " + uri);
                }
                return response.body();
            } catch (IOException e) {
                throw new JwtValidationException("JWKS fetch failed from " + uri, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JwtValidationException("JWKS fetch interrupted", e);
            }
        }
    }

    /** File source. */
    final class File implements JwksSource {
        private final Path path;

        File(Path path) {
            this.path = path;
        }

        @Override
        public byte[] fetch() throws JwtValidationException {
            try {
                return Files.readAllBytes(path);
            } catch (IOException e) {
                throw new JwtValidationException("JWKS file unreadable: " + path, e);
            }
        }
    }
}
