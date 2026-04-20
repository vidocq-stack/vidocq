package fr.vidocq.vidocq.ext.rest.cassini.internal;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.vauban.core.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Pont Chappe ↔ runtime JAX-RS Cassini.
 *
 * <p>Pour chaque requête HTTP reçue, on :</p>
 * <ol>
 *   <li>active le scope CDI {@code @RequestScoped} via {@link RequestContext};</li>
 *   <li>normalise le chemin (strip du contextPath déjà fait par Chappe);</li>
 *   <li>matche la {@link ResourceMethod} via {@link UriRouter};</li>
 *   <li>invoque la méthode via {@link Invoker} ;</li>
 *   <li>retourne 404 ou 405 si pas de match.</li>
 * </ol>
 */
public final class CassiniRestBridge implements Handler {

    private static final System.Logger LOG = System.getLogger(CassiniRestBridge.class.getName());

    private final UriRouter router;
    private final Invoker invoker;
    private final RequestContext requestContext;

    public CassiniRestBridge(UriRouter router, Invoker invoker) {
        this(router, invoker, new RequestContext());
    }

    public CassiniRestBridge(UriRouter router, Invoker invoker, RequestContext requestContext) {
        this.router = router;
        this.invoker = invoker;
        this.requestContext = requestContext;
    }

    @Override
    public Response handle(Request request) throws Exception {
        String verb = request.method().name();
        String path = normalize(request.pathInfo());
        Optional<ResourceMethod> match = router.match(verb, path);

        if (match.isEmpty()) {
            List<String> allowed = router.methodsAllowedFor(path);
            if (!allowed.isEmpty()) {
                return Response.builder()
                        .status(StatusCode.METHOD_NOT_ALLOWED)
                        .header("Allow", String.join(", ", allowed))
                        .body(Body.empty())
                        .build();
            }
            return Response.builder()
                    .status(StatusCode.NOT_FOUND)
                    .header("Content-Type", "text/plain;charset=utf-8")
                    .body(Body.of(("No resource matches " + verb + " " + path).getBytes(StandardCharsets.UTF_8)))
                    .build();
        }

        ResourceMethod route = match.get();
        Object[] holder = new Object[1];
        try {
            requestContext.runInScope(() -> {
                try {
                    holder[0] = invoker.invoke(route);
                } catch (Exception e) {
                    holder[0] = e;
                }
            });
            if (holder[0] instanceof Exception ex) throw ex;
            return (Response) holder[0];
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Cassini handler error on " + verb + " " + path, e);
            return Response.builder()
                    .status(StatusCode.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "text/plain;charset=utf-8")
                    .body(Body.of(e.getMessage() == null ? "Internal Server Error"
                            : e.getMessage()))
                    .build();
        }
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        return raw;
    }
}
