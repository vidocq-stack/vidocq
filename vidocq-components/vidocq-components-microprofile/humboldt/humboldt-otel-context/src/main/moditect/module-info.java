module io.opentelemetry.context {
    requires java.logging;

    exports io.opentelemetry.context;
    exports io.opentelemetry.context.propagation;

    uses io.opentelemetry.context.ContextStorageProvider;
}

