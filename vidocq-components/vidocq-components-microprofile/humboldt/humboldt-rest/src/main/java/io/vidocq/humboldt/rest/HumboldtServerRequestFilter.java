/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.humboldt.rest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;

/**
 * {@link ContainerRequestFilter} that starts a SERVER span for each incoming HTTP
 * request, after extracting any W3C {@code traceparent}.
 *
 * <p>OTel HTTP semantic conventions 1.27+ set on the span:</p>
 * <ul>
 *   <li>{@code http.request.method} — GET/POST/...</li>
 *   <li>{@code http.route} — parameterized template (e.g. {@code /users/{id}}),
 *       rebuilt from {@link UriInfo#getMatchedTemplates()} and the base path</li>
 *   <li>{@code url.path} — full path including the context root, without query string</li>
 *   <li>{@code url.query} — query string without the leading {@code ?} (if present)</li>
 *   <li>{@code url.scheme} — http/https</li>
 *   <li>{@code server.address} — host of the request URI</li>
 *   <li>{@code server.port} — port of the request URI (omitted if it is the scheme default)</li>
 * </ul>
 *
 * <p>Span name: {@code "<METHOD> <route>"} (e.g. {@code "GET /users/{id}"}),
 * in accordance with the OTel HTTP server semconv.</p>
 *
 * <p>The created {@link Span} is stored in the {@link #SPAN_PROPERTY} property of the
 * {@link ContainerRequestContext} for later retrieval by
 * {@link HumboldtServerResponseFilter}. The associated {@link Scope} is stored in
 * {@link #SCOPE_PROPERTY} for symmetrical closing.</p>
 */
@Provider
public class HumboldtServerRequestFilter implements ContainerRequestFilter {

    public static final String SPAN_PROPERTY = "io.vidocq.humboldt.rest.span";
    public static final String SCOPE_PROPERTY = "io.vidocq.humboldt.rest.scope";

    static final AttributeKey<String> HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    static final AttributeKey<String> URL_QUERY = AttributeKey.stringKey("url.query");
    static final AttributeKey<String> URL_SCHEME = AttributeKey.stringKey("url.scheme");
    static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");

    /**
     * Injected by JAX-RS to retrieve the class and method of the matched resource
     * (post-matching only — this filter is not {@code @PreMatching}).
     * Used to reconstruct {@code http.route} via introspection of {@code @Path} annotations.
     */
    @jakarta.ws.rs.core.Context
    ResourceInfo resourceInfo;

    private static final TextMapGetter<ContainerRequestContext> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(ContainerRequestContext carrier) {
            return carrier.getHeaders().keySet();
        }

        @Override
        public String get(ContainerRequestContext carrier, String key) {
            if (carrier == null) return null;
            List<String> values = carrier.getHeaders().get(key);
            return (values == null || values.isEmpty()) ? null : values.getFirst();
        }
    };

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Propagator from the Humboldt global — allows custom propagators declared
        // via SPI ConfigurablePropagatorProvider (MP Telemetry §3.3, cluster D).
        // Falls back to W3CPropagators if GlobalOpenTelemetry is not initialised (unit test
        // without MP Telemetry bootstrap, or tooling that does not configure the runtime).
        OpenTelemetry otel = GlobalOpenTelemetry.get();
        TextMapPropagator propagator = otel.getPropagators().getTextMapPropagator();
        if (propagator == TextMapPropagator.noop()) {
            propagator = W3CPropagators.textMap();
        }
        Context parent = propagator.extract(Context.current(), requestContext, HEADER_GETTER);

        String method = requestContext.getMethod();
        UriInfo uriInfo = requestContext.getUriInfo();
        URI requestUri = uriInfo.getRequestUri();
        URI baseUri = uriInfo.getBaseUri();

        // url.path must include the context root (base path) because the OTel HTTP spec expects
        // the full path as seen by the client. Some stacks (including in-process Cassini)
        // return a requestUri stripped of the context — reconstruct from baseUri + getPath().
        String urlPath = buildFullPath(uriInfo);
        String urlQuery = requestUri.getRawQuery(); // null if absent
        String scheme = (baseUri != null) ? baseUri.getScheme() : requestUri.getScheme();
        String serverAddress = (baseUri != null) ? baseUri.getHost() : requestUri.getHost();
        int serverPort = (baseUri != null) ? baseUri.getPort() : requestUri.getPort();
        String route = buildRoute(uriInfo, resourceInfo);
        if (route == null) route = urlPath; // non-templated fallback when ResourceInfo is absent
        String spanName = method + " " + route;

        SpanBuilder builder = tracer().spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .setAttribute(HTTP_REQUEST_METHOD, method)
                .setAttribute(URL_PATH, urlPath);
        if (urlQuery != null) builder.setAttribute(URL_QUERY, urlQuery);
        if (scheme != null) builder.setAttribute(URL_SCHEME, scheme);
        if (route != null) builder.setAttribute(HTTP_ROUTE, route);
        if (serverAddress != null) builder.setAttribute(SERVER_ADDRESS, serverAddress);
        if (serverPort > 0 && !isDefaultPortForScheme(serverPort, scheme)) {
            builder.setAttribute(SERVER_PORT, (long) serverPort);
        }

        Span span = builder.startSpan();
        // IMPORTANT: use parent.with(span).makeCurrent() — not span.makeCurrent() —
        // to propagate the baggage extracted from the HTTP header into the current Context.
        // Without this, Baggage.current() in the resource method would return Baggage.empty()
        // even when the client sent a `baggage:` header (see BaggageTest which POSTs to the
        // /baggage endpoint with header baggage=user=naruto and expects the resource to read
        // baggage.getEntryValue("user") == "naruto").
        Scope scope = parent.with(span).makeCurrent();
        requestContext.setProperty(SPAN_PROPERTY, span);
        requestContext.setProperty(SCOPE_PROPERTY, scope);
        requestContext.setProperty(START_NANOS_PROPERTY, System.nanoTime());
        if (route != null) requestContext.setProperty(HTTP_ROUTE_PROPERTY, route);
        if (scheme != null) requestContext.setProperty(URL_SCHEME_PROPERTY, scheme);
    }

    /** Templated HTTP route property — used by {@link HumboldtServerResponseFilter} for the Histogram. */
    public static final String HTTP_ROUTE_PROPERTY = "io.vidocq.humboldt.rest.httpRoute";
    /** url.scheme property — used by {@link HumboldtServerResponseFilter} for the Histogram. */
    public static final String URL_SCHEME_PROPERTY = "io.vidocq.humboldt.rest.urlScheme";

    /** Property used by {@link HumboldtServerResponseFilter} to compute the HTTP duration. */
    public static final String START_NANOS_PROPERTY = "io.vidocq.humboldt.rest.startNanos";

    /**
     * Reconstructs the {@code http.route} from the {@link Path} annotations on the matched
     * resource class and resource method (via {@link ResourceInfo}), prefixed with the
     * deployment base path.
     * <p>
     * Example: class {@code @Path("/parent")} + method {@code @Path("/{id}")},
     * call {@code /context-root/parent/123} → route = {@code "/context-root/parent/{id}"}.
     * <p>
     * Note: the standard JAX-RS spec does not expose {@code getMatchedTemplates()}
     * (only present in RestEasy), hence the manual introspection.
     *
     * @return the templated route, or {@code null} if no resource was matched
     *         (pre-matching filter or 404 error).
     */
    private static String buildRoute(UriInfo uriInfo, ResourceInfo resourceInfo) {
        if (resourceInfo == null) return null;
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Method resourceMethod = resourceInfo.getResourceMethod();
        if (resourceClass == null && resourceMethod == null) return null;

        String base = uriInfo.getBaseUri().getRawPath();
        if (base == null) base = "/";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        StringBuilder sb = new StringBuilder(base);
        if (resourceClass != null) {
            Path classPath = resourceClass.getAnnotation(Path.class);
            if (classPath != null) appendPathSegment(sb, classPath.value());
        }
        if (resourceMethod != null) {
            Path methodPath = resourceMethod.getAnnotation(Path.class);
            if (methodPath != null) appendPathSegment(sb, methodPath.value());
        }
        return sb.toString();
    }

    private static void appendPathSegment(StringBuilder sb, String segment) {
        if (segment == null || segment.isEmpty()) return;
        // Normalisation to avoid double slashes: e.g. @Path("/")
        // on the class + @Path("/span") on the method → "/ctx/" + "/span" = "/ctx//span".
        boolean sbEndsSlash = sb.length() > 0 && sb.charAt(sb.length() - 1) == '/';
        boolean segStartsSlash = segment.startsWith("/");
        if (sbEndsSlash && segStartsSlash) {
            sb.append(segment, 1, segment.length());
        } else if (!sbEndsSlash && !segStartsSlash) {
            sb.append('/').append(segment);
        } else {
            sb.append(segment);
        }
    }

    /**
     * Reconstructs the absolute path as seen by the client: {@code baseUri.path + uriInfo.path}.
     * <p>
     * Avoids losing the context root on some in-process JAX-RS stacks that return
     * an already-stripped {@code requestUri} (Cassini, Helidon embedded).
     */
    private static String buildFullPath(UriInfo uriInfo) {
        String relative = uriInfo.getPath(); // without leading slash
        URI base = uriInfo.getBaseUri();
        String basePath = (base != null) ? base.getRawPath() : "/";
        if (basePath == null || basePath.isEmpty()) basePath = "/";
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        if (relative == null || relative.isEmpty()) {
            return basePath.isEmpty() ? "/" : basePath;
        }
        if (relative.startsWith("/")) return basePath + relative;
        return basePath + "/" + relative;
    }

    private static boolean isDefaultPortForScheme(int port, String scheme) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    protected Tracer tracer() {
        return openTelemetry().getTracer("io.vidocq.humboldt.rest");
    }

    protected OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    protected io.opentelemetry.context.propagation.ContextPropagators propagatorsAdapter() {
        return W3CPropagators.get();
    }

    /**
     * Test hook — overridable to point to a custom propagator.
     * @return the default W3C adapter.
     */
    private W3CPropagatorsWrapper propagators() {
        return new W3CPropagatorsWrapper();
    }

    /** Internal wrapper — exposes {@code textMap()} in a testable way. */
    static class W3CPropagatorsWrapper {
        TextMapPropagator textMap() {
            return W3CPropagators.textMap();
        }
    }
}
