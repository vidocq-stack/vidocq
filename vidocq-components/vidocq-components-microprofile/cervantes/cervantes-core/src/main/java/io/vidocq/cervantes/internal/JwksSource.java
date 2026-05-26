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
 * Source d'octets d'un document JWK Set ({@code mp.jwt.verify.publickey.location}). Abstrait
 * l'origine (HTTP ou fichier) pour {@link JwksKeyResolver}.
 */
@FunctionalInterface
interface JwksSource {

    /** @return le contenu brut du JWKS ; lève si la source est injoignable/illisible. */
    byte[] fetch() throws JwtValidationException;

    /**
     * Construit une source depuis une location : {@code http(s)://…} → HTTP (HttpClient JDK,
     * virtual threads), sinon traitée comme un chemin de fichier (avec ou sans schéma {@code file:}).
     */
    static JwksSource fromLocation(String location, HttpClient httpClient, Duration timeout) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return new Http(URI.create(location), httpClient, timeout);
        }
        Path path = location.startsWith("file:") ? Path.of(URI.create(location)) : Path.of(location);
        return new File(path);
    }

    /** Variante avec un {@link HttpClient} par défaut (connect timeout 5 s). */
    static JwksSource fromLocation(String location) {
        return fromLocation(
                location,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(5));
    }

    /** Source HTTP : un GET bloquant sur un virtual thread (pas de pinning). */
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
                        .header("Accept", "application/json")
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

    /** Source fichier. */
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
