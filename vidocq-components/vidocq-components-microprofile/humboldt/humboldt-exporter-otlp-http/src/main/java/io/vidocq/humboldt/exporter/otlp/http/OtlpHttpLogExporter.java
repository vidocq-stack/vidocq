package io.vidocq.humboldt.exporter.otlp.http;

import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpHttpJsonSender;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonLogEncoder;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTLP/HTTP-JSON exporter for LogRecord — delegates transport to
 * {@link OtlpHttpJsonSender}.
 *
 * <p>Default endpoint: {@code http://localhost:4318/v1/logs}.</p>
 */
public final class OtlpHttpLogExporter implements LogRecordExporter {

    private final OtlpHttpJsonSender sender;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private OtlpHttpLogExporter(OtlpHttpJsonSender sender) {
        this.sender = sender;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> records) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        if (records.isEmpty()) return CompletableResultCode.ofSuccess();
        return sender.send(OtlpJsonLogEncoder.encode(records));
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
        private URI endpoint = URI.create("http://localhost:4318/v1/logs");
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private int maxRetries = 3;

        public Builder setEndpoint(String url) { this.endpoint = URI.create(url); return this; }
        public Builder addHeader(String n, String v) { if (n != null && v != null) headers.put(n, v); return this; }
        public Builder setRequestTimeout(Duration t) { if (t != null && !t.isNegative() && !t.isZero()) requestTimeout = t; return this; }
        public Builder setConnectTimeout(Duration t) { if (t != null && !t.isNegative() && !t.isZero()) connectTimeout = t; return this; }
        public Builder setMaxRetries(int n) { if (n >= 0) maxRetries = n; return this; }

        public OtlpHttpLogExporter build() {
            OtlpHttpJsonSender sender = OtlpHttpJsonSender.builder()
                    .setEndpoint(endpoint)
                    .addAllHeaders(headers)
                    .setRequestTimeout(requestTimeout)
                    .setConnectTimeout(connectTimeout)
                    .setMaxRetries(maxRetries)
                    .setSignalLabel("logs")
                    .build();
            return new OtlpHttpLogExporter(sender);
        }
    }
}
