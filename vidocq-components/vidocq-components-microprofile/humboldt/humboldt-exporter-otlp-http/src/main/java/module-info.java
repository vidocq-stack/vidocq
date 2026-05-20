/**
 * Humboldt Exporter OTLP HTTP — pousse les spans vers un OTel Collector
 * via OTLP/HTTP-JSON.
 *
 * <p>Implémentation M3 minimale viable :</p>
 * <ul>
 *   <li>encoding OTLP/JSON (StringBuilder direct, schéma resourceSpans/scopeSpans/spans/attributes)</li>
 *   <li>transport {@link java.net.http.HttpClient} du JDK</li>
 *   <li>retry simple sur 5xx (exponentiel borné)</li>
 * </ul>
 *
 * <p>La variante OTLP/HTTP-protobuf et la commutation transport vers
 * {@code chappe-client} sont prévues en M3b (cf. PLAN.md §13).</p>
 */
module io.vidocq.humboldt.exporter.otlp.http {

    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.vidocq.humboldt.sdk.metric;
    requires java.net.http;
    requires java.logging;

    // Pour les tests E2E qui utilisent com.sun.net.httpserver (HttpServer JDK in-process).
    // 'static' = compile-time uniquement ; le module reste hors graphe runtime.
    requires static jdk.httpserver;

    exports io.vidocq.humboldt.exporter.otlp.http;
}
