package io.vidocq.dirac.rest;

import io.vidocq.dirac.internal.JsonMetricsFormatter;
import io.vidocq.dirac.internal.OpenMetricsFormatter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryScope;

import java.util.List;

/**
 * REST /metrics endpoint — MicroProfile Metrics 5.1.1 §2.3.
 *
 * <p>Produces OpenMetrics (text/plain) by default or JSON (application/json)
 * according to the {@code Accept} header normalized by {@link ContentNegotiationFilter}.</p>
 *
 * <p>The {@code format*()} methods are package-visible for unit tests
 * (direct injection of registries without a JAX-RS container).</p>
 */
@Path("/metrics")
@ApplicationScoped
public class MetricsResource {

    static final String OPENMETRICS_TYPE = "text/plain;version=0.0.4;charset=utf-8";
    private static final String EOF_MARKER = "# EOF\n";

    /** Result of a formatting operation — body + effective media type. */
    record Formatted(String body, String mediaType) {}

    // Field injection — CDI sets these; test constructor overrides them.
    @Inject
    @RegistryScope
    MetricRegistry appRegistry;

    @Inject
    @RegistryScope(scope = MetricRegistry.BASE_SCOPE)
    MetricRegistry baseRegistry;

    @Inject
    @RegistryScope(scope = MetricRegistry.VENDOR_SCOPE)
    MetricRegistry vendorRegistry;

    /** CDI / default constructor. */
    public MetricsResource() {}

    /** Test constructor — sets all three registries without CDI. */
    MetricsResource(MetricRegistry appRegistry, MetricRegistry baseRegistry, MetricRegistry vendorRegistry) {
        this.appRegistry = appRegistry;
        this.baseRegistry = baseRegistry;
        this.vendorRegistry = vendorRegistry;
    }

    // -------------------------------------------------------------------------
    // JAX-RS endpoints
    // -------------------------------------------------------------------------

    /** §2.3.1 — GET /metrics : all metrics from all scopes. */
    @GET
    @Produces({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
    public Response getAllMetrics(@HeaderParam(HttpHeaders.ACCEPT) String accept) {
        var fmt = formatAll(accept);
        return Response.ok(fmt.body(), fmt.mediaType()).build();
    }

    /** §2.3.2 — GET /metrics/{scope} : metrics for a scope (404 if the scope is unknown). */
    @GET
    @Path("/{scope}")
    @Produces({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
    public Response getByScope(@PathParam("scope") String scope,
                               @HeaderParam(HttpHeaders.ACCEPT) String accept) {
        var fmt = formatScope(scope, accept);
        if (fmt == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        return Response.ok(fmt.body(), fmt.mediaType()).build();
    }

    /** §2.3.3 — GET /metrics/{scope}/{name} : single metric by name (404 if absent). */
    @GET
    @Path("/{scope}/{name}")
    @Produces({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
    public Response getByName(@PathParam("scope") String scope,
                              @PathParam("name") String name,
                              @HeaderParam(HttpHeaders.ACCEPT) String accept) {
        var fmt = formatMetric(scope, name, accept);
        if (fmt == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        return Response.ok(fmt.body(), fmt.mediaType()).build();
    }

    // -------------------------------------------------------------------------
    // Package-visible formatting logic — testable without RuntimeDelegate
    // -------------------------------------------------------------------------

    Formatted formatAll(String accept) {
        if (wantsJson(accept)) {
            return new Formatted(buildAllJson(), MediaType.APPLICATION_JSON);
        }
        return new Formatted(buildAllText(), OPENMETRICS_TYPE);
    }

    Formatted formatScope(String scope, String accept) {
        var registry = registryForScope(scope);
        if (registry == null) {
            return null;
        }
        if (wantsJson(accept)) {
            return new Formatted(new JsonMetricsFormatter().format(registry), MediaType.APPLICATION_JSON);
        }
        return new Formatted(new OpenMetricsFormatter().format(registry), OPENMETRICS_TYPE);
    }

    Formatted formatMetric(String scope, String name, String accept) {
        var registry = registryForScope(scope);
        if (registry == null) {
            return null;
        }
        var filtered = registry.getMetrics((id, m) -> id.getName().equals(name));
        if (filtered.isEmpty()) {
            return null;
        }
        if (wantsJson(accept)) {
            return new Formatted(new JsonMetricsFormatter().format(filtered), MediaType.APPLICATION_JSON);
        }
        return new Formatted(new OpenMetricsFormatter().format(filtered, registry), OPENMETRICS_TYPE);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildAllText() {
        var formatter = new OpenMetricsFormatter();
        var sb = new StringBuilder();
        for (var registry : List.of(appRegistry, baseRegistry, vendorRegistry)) {
            var text = formatter.format(registry);
            // Strip per-registry EOF — a single EOF is appended at the end
            if (text.endsWith(EOF_MARKER)) {
                sb.append(text, 0, text.length() - EOF_MARKER.length());
            } else {
                sb.append(text);
            }
        }
        return sb.append(EOF_MARKER).toString();
    }

    private String buildAllJson() {
        var json = new JsonMetricsFormatter();
        return "{\"application\":" + json.format(appRegistry)
                + ",\"base\":" + json.format(baseRegistry)
                + ",\"vendor\":" + json.format(vendorRegistry)
                + "}";
    }

    private MetricRegistry registryForScope(String scope) {
        if (scope == null) {
            return null;
        }
        return switch (scope.toLowerCase()) {
            case "application" -> appRegistry;
            case "base" -> baseRegistry;
            case "vendor" -> vendorRegistry;
            default -> null;
        };
    }

    private static boolean wantsJson(String accept) {
        return accept != null && accept.contains(MediaType.APPLICATION_JSON);
    }
}
