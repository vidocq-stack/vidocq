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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

/** {@link ExternalResources} backed by one shared, lazily built {@link HttpClient} and {@link URLConnection}. */
final class JdkExternalResources implements ExternalResources {

    static final ExternalResources INSTANCE = new JdkExternalResources();

    /**
     * Lazy holder: the client (and its virtual-thread executor) is created on first absolute
     * http(s) import, never for relative imports.
     */
    private static final class Client {
        static final HttpClient INSTANCE = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    private JdkExternalResources() {
    }

    @Override
    public Reader open(String absoluteUrl, String charEncoding) throws IOException {
        URI uri;
        try {
            uri = URI.create(absoluteUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + absoluteUrl, e);
        }
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return openHttp(uri, absoluteUrl, charEncoding);
        }
        URLConnection connection = uri.toURL().openConnection();
        InputStream in = connection.getInputStream();
        return reader(in, charEncoding, connection.getContentType());
    }

    private static Reader openHttp(URI uri, String absoluteUrl, String charEncoding) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = Client.INSTANCE.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while fetching " + absoluteUrl);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            response.body().close();
            throw new IOException("HTTP status " + status + " for " + absoluteUrl);
        }
        return reader(response.body(), charEncoding, response.headers().firstValue("Content-Type").orElse(null));
    }

    private static Reader reader(InputStream in, String charEncoding, String contentType) throws IOException {
        try {
            return new InputStreamReader(in, ExternalResources.charsetOf(charEncoding, contentType));
        } catch (IllegalArgumentException e) {
            in.close();
            throw new IOException("Unsupported character encoding: " + charEncoding, e);
        }
    }
}
