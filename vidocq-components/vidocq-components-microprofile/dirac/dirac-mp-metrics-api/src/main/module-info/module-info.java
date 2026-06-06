module microprofile.metrics.api {
    // @Counted/@Timed are meta-annotated @jakarta.interceptor.InterceptorBinding. Without reading
    // jakarta.interceptor, that meta-annotation is unresolvable on the strict module path, so a CDI
    // container cannot recognise the binding (interception silently never fires — invisible on the
    // class path / TCK). transitive so consumers of the annotations see the binding too.
    requires transitive jakarta.interceptor;

    exports org.eclipse.microprofile.metrics;
    exports org.eclipse.microprofile.metrics.annotation;
}

