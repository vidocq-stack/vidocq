/**
 * Humboldt OTLP HTTP Exporter — pushes spans to an OTel Collector
 * via OTLP/HTTP-JSON.
 *
 * <p>M3 minimum viable implementation:</p>
 * <ul>
 *   <li>OTLP/JSON encoding (direct StringBuilder, resourceSpans/scopeSpans/spans/attributes schema)</li>
 *   <li>transport via the JDK {@link java.net.http.HttpClient}</li>
 *   <li>simple retry on 5xx (bounded exponential backoff)</li>
 * </ul>
 *
 * <p>The OTLP/HTTP-protobuf variant and the transport switch to
 * {@code chappe-client} are planned for M3b (see PLAN.md §13).</p>
 */
module io.vidocq.humboldt.exporter.otlp.http {

    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.vidocq.humboldt.sdk.metric;
    requires transitive io.vidocq.humboldt.sdk.log;
    requires java.net.http;
    requires java.logging;

    // For E2E tests that use com.sun.net.httpserver (JDK in-process HttpServer).
    // 'static' = compile-time only; the module remains outside the runtime graph.
    requires static jdk.httpserver;

    exports io.vidocq.humboldt.exporter.otlp.http;
}
