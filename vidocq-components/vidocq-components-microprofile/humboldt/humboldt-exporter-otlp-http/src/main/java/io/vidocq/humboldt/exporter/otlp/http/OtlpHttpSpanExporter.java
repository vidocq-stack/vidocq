package io.vidocq.humboldt.exporter.otlp.http;

import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpHttpJsonSender;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonEncoder;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTLP/HTTP-JSON exporter for spans — delegates transport to
 * {@link OtlpHttpJsonSender} (shared with metrics and logs).
 *
 * <p>Default endpoint: {@code http://localhost:4318/v1/traces}.</p>
 */
public final class OtlpHttpSpanExporter implements SpanExporter {

    private final OtlpHttpJsonSender sender;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private OtlpHttpSpanExporter(OtlpHttpJsonSender sender) {
        this.sender = sender;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        if (spans.isEmpty()) return CompletableResultCode.ofSuccess();
        return sender.send(OtlpJsonEncoder.encode(spans));
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

    /**
     * Bounded exponential backoff — delegated to {@link OtlpHttpJsonSender#computeBackoffMillis(int)}.
     * Kept public for backwards compatibility (used by E2E tests).
     */
    public static long computeBackoffMillis(int attempt) {
        return OtlpHttpJsonSender.computeBackoffMillis(attempt);
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
            OtlpHttpJsonSender sender = OtlpHttpJsonSender.builder()
                    .setEndpoint(endpoint)
                    .addAllHeaders(headers)
                    .setRequestTimeout(requestTimeout)
                    .setConnectTimeout(connectTimeout)
                    .setMaxRetries(maxRetries)
                    .setSignalLabel("traces")
                    .build();
            return new OtlpHttpSpanExporter(sender);
        }
    }
}
