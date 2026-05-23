module io.opentelemetry.api {
    requires transitive io.opentelemetry.context;
    requires java.logging;

    exports io.opentelemetry.api;
    exports io.opentelemetry.api.baggage;
    exports io.opentelemetry.api.baggage.propagation;
    exports io.opentelemetry.api.common;
    exports io.opentelemetry.api.logs;
    exports io.opentelemetry.api.metrics;
    exports io.opentelemetry.api.trace;
    exports io.opentelemetry.api.trace.propagation;
}



