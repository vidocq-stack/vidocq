package io.vidocq.humboldt.exporter.otlp.http.internal;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Shared HTTP/JSON sender for the 3 OTLP exporters (traces / metrics / logs).
 *
 * <p>Encapsulates {@link HttpClient} + endpoint + headers + timeouts + bounded
 * exponential retry. The signal-specific exporters (span/metric/log) delegate here
 * to send an already-encoded JSON payload.</p>
 *
 * <p>Async: each {@link #send(String)} launches a virtual thread that executes
 * the HTTP request + retry. Returns a {@link CompletableResultCode} that completes
 * at the end (success / definitive failure after {@code maxRetries}).</p>
 */
public final class OtlpHttpJsonSender {

    private static final Logger LOG = System.getLogger(OtlpHttpJsonSender.class.getName());

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final HttpClient client;
    private final String signalLabel;

    private OtlpHttpJsonSender(Builder b) {
        this.endpoint = b.endpoint;
        this.headers = Map.copyOf(b.headers);
        this.requestTimeout = b.requestTimeout;
        this.maxRetries = b.maxRetries;
        this.signalLabel = b.signalLabel;
        this.client = HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param jsonBody already-encoded OTLP/JSON payload
     * @return an asynchronous result — success if status 2xx (possibly after retry).
     */
    public CompletableResultCode send(String jsonBody) {
        HttpRequest.Builder reqB = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        headers.forEach(reqB::header);
        HttpRequest req = reqB.build();

        CompletableResultCode result = new CompletableResultCode();
        Thread.ofVirtual().start(() -> sendWithRetry(req, result, 0));
        return result;
    }

    private void sendWithRetry(HttpRequest req, CompletableResultCode result, int attempt) {
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc >= 200 && sc < 300) { result.succeed(); return; }
            if (sc >= 500 && attempt < maxRetries) {
                long backoffMs = computeBackoffMillis(attempt);
                LOG.log(Level.WARNING,
                        "OTLP {0} HTTP {1} (attempt {2}/{3}) - retry in {4}ms",
                        signalLabel, sc, attempt + 1, maxRetries, backoffMs);
                Thread.sleep(backoffMs);
                sendWithRetry(req, result, attempt + 1);
                return;
            }
            LOG.log(Level.WARNING, "OTLP {0} HTTP permanent rejection: {1} — {2}",
                    signalLabel, sc, resp.body());
            result.fail();
        } catch (Exception e) {
            if (attempt < maxRetries) {
                long backoffMs = computeBackoffMillis(attempt);
                LOG.log(Level.WARNING,
                        "OTLP {0} send failed (attempt {1}/{2}): {3} — retrying in {4}ms",
                        signalLabel, attempt + 1, maxRetries, e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                    sendWithRetry(req, result, attempt + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    result.fail();
                }
                return;
            }
            LOG.log(Level.ERROR, "OTLP " + signalLabel + " send permanently failed", e);
            result.fail();
        }
    }

    /**
     * Bounded exponential backoff: 100ms × 2^attempt, capped at 5s.
     * Public so tests can perform sanity checks.
     */
    public static long computeBackoffMillis(int attempt) {
        return Math.min(100L << attempt, 5_000L);
    }

    public static final class Builder {
        private URI endpoint;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private int maxRetries = 3;
        private String signalLabel = "?";

        public Builder setEndpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder addHeader(String name, String value) {
            if (name != null && value != null) headers.put(name, value);
            return this;
        }

        public Builder addAllHeaders(Map<String, String> all) {
            if (all != null) headers.putAll(all);
            return this;
        }

        public Builder setRequestTimeout(Duration timeout) {
            if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
                this.requestTimeout = timeout;
            }
            return this;
        }

        public Builder setConnectTimeout(Duration timeout) {
            if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
                this.connectTimeout = timeout;
            }
            return this;
        }

        public Builder setMaxRetries(int n) {
            if (n >= 0) this.maxRetries = n;
            return this;
        }

        /** Label used for retry log messages (e.g. {@code "traces"}, {@code "metrics"}, {@code "logs"}). */
        public Builder setSignalLabel(String label) {
            if (label != null) this.signalLabel = label;
            return this;
        }

        public OtlpHttpJsonSender build() {
            if (endpoint == null) throw new IllegalStateException("endpoint manquant");
            return new OtlpHttpJsonSender(this);
        }
    }
}
