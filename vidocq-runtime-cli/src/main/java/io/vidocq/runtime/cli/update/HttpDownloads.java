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
package io.vidocq.runtime.cli.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** HTTP access to the Maven repositories for {@code vidocq update}. */
public final class HttpDownloads implements AutoCloseable {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Each task on a started virtual thread. (ThreadFactory::newThread would only
            // create the thread, so no request would ever complete.)
            .executor(task -> Thread.ofVirtual().start(task))
            .build();

    /** The body of {@code url}, empty unless it answers 200. */
    public Optional<String> fetch(String url) {
        try {
            HttpResponse<String> response = client.send(request(url, Duration.ofSeconds(15)),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Saves {@code url} to {@code file}. */
    public void download(String url, Path file) throws IOException {
        try {
            HttpResponse<Path> response = client.send(request(url, Duration.ofMinutes(5)),
                    HttpResponse.BodyHandlers.ofFile(file));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private static HttpRequest request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    }

    @Override
    public void close() {
        client.close();
    }
}
