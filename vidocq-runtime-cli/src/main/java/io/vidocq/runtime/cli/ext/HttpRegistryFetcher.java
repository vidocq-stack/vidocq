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
package io.vidocq.runtime.cli.ext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link ExtensionRegistry.Fetcher} backed by {@code java.net.http.HttpClient}.
 * Performs a single GET against the registry endpoint with a short timeout and
 * returns {@link Optional#empty()} on any failure (offline, timeout, non-200) so
 * the caller can fall back to the cache / built-in catalog.
 *
 * <p>The blocking request runs on a virtual thread per the CLI's concurrency policy.</p>
 */
public final class HttpRegistryFetcher implements ExtensionRegistry.Fetcher {

    /** Default Vidocq extension registry endpoint. */
    public static final String DEFAULT_URL = "https://registry.vidocq.dev/extensions.json";

    private final URI endpoint;
    private final Duration timeout;

    public HttpRegistryFetcher() {
        this(URI.create(DEFAULT_URL), Duration.ofSeconds(3));
    }

    public HttpRegistryFetcher(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public Optional<String> fetch() {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .executor(Thread.ofVirtual().factory()::newThread)
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.of(response.body());
            }
            return Optional.empty();
        } catch (Exception e) {
            // Offline, DNS failure, timeout, interrupted… — degrade gracefully.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
