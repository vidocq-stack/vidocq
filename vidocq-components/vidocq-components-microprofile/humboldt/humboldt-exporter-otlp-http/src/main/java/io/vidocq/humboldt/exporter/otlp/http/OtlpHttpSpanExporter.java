package io.vidocq.humboldt.exporter.otlp.http;

import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonEncoder;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exporter OTLP/HTTP-JSON.
 *
 * <p>POST le payload {@link OtlpJsonEncoder} vers l'endpoint configuré
 * (typiquement {@code http://localhost:4318/v1/traces}) avec
 * {@code Content-Type: application/json}. Retry borné sur 5xx.</p>
 *
 * <p>Transport : {@link HttpClient} du JDK. La commutation vers
 * {@code chappe-client} (HTTP/1.1 + H2 zéro-dep maison) viendra en M3b.</p>
 */
public final class OtlpHttpSpanExporter implements SpanExporter {

    private static final Logger LOG = System.getLogger(OtlpHttpSpanExporter.class.getName());

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final HttpClient client;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private OtlpHttpSpanExporter(Builder b) {
        this.endpoint = b.endpoint;
        this.headers = Map.copyOf(b.headers);
        this.requestTimeout = b.requestTimeout;
        this.maxRetries = b.maxRetries;
        this.client = HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        if (spans.isEmpty()) return CompletableResultCode.ofSuccess();

        String body = OtlpJsonEncoder.encode(spans);
        HttpRequest.Builder reqB = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
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
            if (sc >= 200 && sc < 300) {
                result.succeed();
                return;
            }
            if (sc >= 500 && attempt < maxRetries) {
                long backoffMs = computeBackoffMillis(attempt);
                LOG.log(Level.WARNING,
                        "OTLP HTTP {0} (tentative {1}/{2}) — retry dans {3}ms",
                        sc, attempt + 1, maxRetries, backoffMs);
                Thread.sleep(backoffMs);
                sendWithRetry(req, result, attempt + 1);
                return;
            }
            LOG.log(Level.WARNING, "OTLP HTTP rejet définitif : {0} — {1}", sc, resp.body());
            result.fail();
        } catch (Exception e) {
            if (attempt < maxRetries) {
                long backoffMs = computeBackoffMillis(attempt);
                LOG.log(Level.WARNING,
                        "OTLP envoi échoué (tentative {0}/{1}) : {2} — retry dans {3}ms",
                        attempt + 1, maxRetries, e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                    sendWithRetry(req, result, attempt + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    result.fail();
                }
                return;
            }
            LOG.log(Level.ERROR, "OTLP envoi définitivement échoué", e);
            result.fail();
        }
    }

    static long computeBackoffMillis(int attempt) {
        // 100, 200, 400, 800, ... plafonné à 5s
        long base = Math.min(100L << attempt, 5_000L);
        return base;
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        stopped.set(true);
        return CompletableResultCode.ofSuccess();
    }

    public static final class Builder {
        private URI endpoint = URI.create("http://localhost:4318/v1/traces");
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private int maxRetries = 3;

        public Builder setEndpoint(String url) {
            this.endpoint = URI.create(url);
            return this;
        }

        public Builder addHeader(String name, String value) {
            if (name != null && value != null) headers.put(name, value);
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

        public OtlpHttpSpanExporter build() {
            return new OtlpHttpSpanExporter(this);
        }
    }
}
