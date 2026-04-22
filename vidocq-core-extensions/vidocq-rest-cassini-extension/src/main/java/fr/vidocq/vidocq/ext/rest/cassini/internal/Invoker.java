package fr.vidocq.vidocq.ext.rest.cassini.internal;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniUriInfo;
import fr.vidocq.vidocq.ext.rest.cassini.internal.filter.CassiniReaderInterceptorContext;
import fr.vidocq.vidocq.ext.rest.cassini.internal.filter.CassiniRequestContext;
import fr.vidocq.vidocq.ext.rest.cassini.internal.filter.CassiniResponseContext;
import fr.vidocq.vidocq.ext.rest.cassini.internal.filter.CassiniWriterInterceptorContext;
import fr.vidocq.vidocq.ext.rest.cassini.internal.filter.FilterRegistry;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Exécute une {@link ResourceMethod} : content-negotiation, lecture du
 * corps via {@link MessageBodyReader}, invocation, sérialisation via
 * {@link MessageBodyWriter}.
 */
public final class Invoker {

    /** Contexte exposé pendant l'appel {@link #resolver} pour permettre à des
     *  résolveurs (ex. harness TCK) de créer des instances avec injection
     *  constructeur §3.1.1. */
    public static final ThreadLocal<MatchResult> CURRENT_MATCH = new ThreadLocal<>();
    public static final ThreadLocal<Request> CURRENT_REQUEST = new ThreadLocal<>();

    private final Function<Class<?>, Object> resolver;
    private final MessageBodyRegistry registry;
    private final ExceptionMapperRegistry exceptionMappers;
    private FilterRegistry filters = new FilterRegistry();

    public void setFilters(FilterRegistry f) { this.filters = f == null ? new FilterRegistry() : f; }
    public FilterRegistry filters() { return filters; }

    public Invoker(Function<Class<?>, Object> resolver) {
        this(resolver, new MessageBodyRegistry(), new ExceptionMapperRegistry());
    }

    public Invoker(Function<Class<?>, Object> resolver, MessageBodyRegistry registry) {
        this(resolver, registry, new ExceptionMapperRegistry());
    }

    public Invoker(Function<Class<?>, Object> resolver, MessageBodyRegistry registry,
                   ExceptionMapperRegistry exceptionMappers) {
        this.resolver = resolver;
        this.registry = registry;
        this.exceptionMappers = exceptionMappers;
    }

    public MessageBodyRegistry registry() { return registry; }
    public ExceptionMapperRegistry exceptionMappers() { return exceptionMappers; }

    public static Invoker forBeanManager(BeanManager bm) {
        return forBeanManager(bm, new MessageBodyRegistry());
    }

    public static Invoker forBeanManager(BeanManager bm, MessageBodyRegistry registry) {
        return forBeanManager(bm, registry, ExceptionMapperRegistry.discover(bm));
    }

    public static Invoker forBeanManager(BeanManager bm, MessageBodyRegistry registry,
                                         ExceptionMapperRegistry exMappers) {
        AnnotationLiteral<Any> any = new AnnotationLiteral<Any>() {};
        return new Invoker(type -> {
            Set<Bean<?>> beans = bm.getBeans(type, any);
            Bean<?> bean = bm.resolve(beans);
            if (bean == null) throw new IllegalStateException("No CDI bean for " + type.getName());
            var cc = bm.createCreationalContext(bean);
            return bm.getReference(bean, type, cc);
        }, registry, exMappers);
    }

    public Response invoke(MatchResult match, Request request) throws Exception {
        return invoke(java.util.List.of(match), request);
    }

    /** Résout la meilleure route parmi les candidats en fonction des Accept/
     *  Content-Type de la requête (§3.7.2). Passe à invoke(MatchResult, Request)
     *  canonique avec le gagnant. */
    public Response invoke(java.util.List<MatchResult> candidates, Request request) throws Exception {
        if (candidates.isEmpty()) throw new IllegalArgumentException("no candidates");
        MatchResult match = pickBestMatch(candidates, request);
        ResourceMethod route = match.method();
        ParamExtractor.setProviders(new fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        try {
            try {
                java.net.URI u = request.uri();
                String scheme = u != null && u.getScheme() != null ? u.getScheme()
                        : (request.isSecure() ? "https" : "http");
                String authority = u != null && u.getAuthority() != null ? u.getAuthority() : null;
                if (authority == null) {
                    String host = request.headers().firstOrNull("Host");
                    if (host != null && !host.isEmpty()) authority = host;
                }
                if (authority == null) authority = "127.0.0.1";
                String ctx = request.contextPath();
                if (ctx == null) ctx = "";
                String basePath = ctx.isEmpty() ? "/" : ctx + "/";
                java.net.URI base = new java.net.URI(scheme + "://" + authority + basePath);
                fr.vidocq.vidocq.ext.rest.cassini.internal.runtime.CassiniResponseBuilder.setBaseUri(base);
            } catch (Exception ignored) {}
            return invokeInternal(match, request, route);
        } finally {
            ParamExtractor.clearProviders();
            FieldInjector.clearFormCache();
            CURRENT_MATCH.remove();
            CURRENT_REQUEST.remove();
            fr.vidocq.vidocq.ext.rest.cassini.internal.runtime.CassiniResponseBuilder.clearBaseUri();
        }
    }

    private Response invokeInternal(MatchResult match, Request request, ResourceMethod route) throws Exception {

        // 1. Negotiation
        String ctHeader = request.headers().firstOrNull("Content-Type");
        MediaType contentType = MediaTypes.parse(ctHeader);
        List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        // §3.7.2 : si la requête a un Content-Type ou un body, filtre sur @Consumes.
        boolean checkConsumes = hasRequestBody(request) || ctHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(contentType, consumes)) {
            return Response.of(StatusCode.UNSUPPORTED_MEDIA_TYPE);
        }

        List<MediaType> accepts = MediaTypes.parseList(request.headers().firstOrNull("Accept"));
        List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(accepts, produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return Response.of(StatusCode.NOT_ACCEPTABLE);
        }
        MediaType chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);

        // 2. Resolve args
        ParamExtractor.ResolvedArgs resolved;
        Object[] args;
        try {
            resolved = ParamExtractor.resolve(route, match, request);
            args = resolved.args();
            if (resolved.bodyIndex() >= 0) {
                Parameter p = route.javaMethod().getParameters()[resolved.bodyIndex()];
                // §4.2.4 : Standard providers DOIVENT retourner un objet non
                // null même pour un body vide (String=="", byte[]=new byte[0],
                // InputStream=empty stream, …). On lit donc toujours via MBR
                // quand un param body est présent.
                args[resolved.bodyIndex()] = readEntity(p, contentType, request);
            }
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, null);
        }

        // 3. Post-matching request filters — marquer le contexte comme
        //    post-matching avant leur exécution pour que les setters
        //    protégés (setRequestUri, setMethod, etc.) lèvent ISE §6.6.
        CassiniRequestContext rctx = null;
        if (!filters.postMatching().isEmpty() || !filters.responseFilters().isEmpty()) {
            rctx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), match.pathParams()));
            rctx.markPostMatching();
            for (var fe : filters.postMatching()) {
                if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
                try { fe.instance().filter(rctx); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
                if (rctx.isAborted()) {
                    return runResponseFiltersAndWrite(rctx, rctx.abortedResponse(), route, chosen);
                }
            }
        }

        // 4. Invoke
        Object target;
        CURRENT_MATCH.set(match);
        CURRENT_REQUEST.set(request);
        try {
            if (route.isLocated()) {
                // Sub-resource locator §3.4.1 : instantier la ressource racine,
                // injecter ses fields, appeler le locator pour obtenir l'instance
                // sous-ressource, puis injecter ses fields.
                Object root = resolver.apply(route.rootBeanClass());
                FieldInjector.inject(root, match, request);
                target = route.locator().invoke(root);
                if (target == null) {
                    return renderWebAppException(
                            new WebApplicationException("Sub-resource locator returned null", 404),
                            route, chosen, rctx);
                }
                FieldInjector.inject(target, match, request);
            } else {
                target = resolver.apply(route.beanClass());
                FieldInjector.inject(target, match, request);
            }
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, rctx);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof WebApplicationException wae) {
                return renderWebAppException(wae, route, chosen, rctx);
            }
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
        Object result;
        try {
            result = route.javaMethod().invoke(target, args);
        } catch (IllegalArgumentException iae) {
            StringBuilder sb = new StringBuilder("Argument mismatch on ")
                    .append(route.beanClass().getName()).append('.')
                    .append(route.javaMethod().getName()).append("(): ");
            Parameter[] ps = route.javaMethod().getParameters();
            for (int i = 0; i < ps.length; i++) {
                sb.append("\n  [").append(i).append("] param=").append(ps[i].getType().getSimpleName())
                        .append(" arg=").append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
            }
            throw new RuntimeException(sb.toString(), iae);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            var mapped = exceptionMappers.map(cause);
            if (mapped.isPresent()) return runResponseFiltersAndWrite(rctx, mapped.get(), route, chosen);
            if (cause instanceof WebApplicationException wae) {
                return renderWebAppException(wae, route, chosen, rctx);
            }
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }

        // 5. Marshal + response filters
        if (rctx != null && !filters.responseFilters().isEmpty()) {
            return runResponseFiltersForResult(rctx, result, route, chosen);
        }
        return marshal(result, route, chosen);
    }

    private Response runResponseFiltersAndWrite(CassiniRequestContext rctx,
                                                jakarta.ws.rs.core.Response userResp,
                                                ResourceMethod route, MediaType chosen) throws IOException {
        int status = userResp.getStatus();
        Object entity = userResp.getEntity();
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        for (var e : userResp.getStringHeaders().entrySet()) for (String v : e.getValue()) headers.add(e.getKey(), v);

        CassiniResponseContext rctx2 = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), headers);
        for (var fe : filters.responseFilters()) {
            if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
            try { fe.instance().filter(rctx, rctx2); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        return writeFromContext(rctx2, route, chosen);
    }

    private Response runResponseFiltersForResult(CassiniRequestContext rctx, Object result,
                                                  ResourceMethod route, MediaType chosen) throws IOException {
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        int status = (result == null) ? 204 : 200;
        Object entity = (result instanceof jakarta.ws.rs.core.Response jr) ? jr.getEntity() : result;
        java.lang.annotation.Annotation[] entityAnnotations = null;
        if (result instanceof jakarta.ws.rs.core.Response jr2) {
            status = jr2.getStatus();
            for (var e : jr2.getStringHeaders().entrySet()) for (String v : e.getValue()) headers.add(e.getKey(), v);
            // Les annotations de l'entity sont internes au CassiniResponse
            // (§6.7.4 : exposées via ContainerResponseContext.getEntityAnnotations).
            if (jr2 instanceof fr.vidocq.vidocq.ext.rest.cassini.internal.runtime.CassiniResponse cr) {
                entityAnnotations = cr.entityAnnotations();
            }
        }
        if ((entityAnnotations == null || entityAnnotations.length == 0) && route.javaMethod() != null) {
            entityAnnotations = route.javaMethod().getAnnotations();
        }

        CassiniResponseContext rctx2 = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), entityAnnotations, headers);
        for (var fe : filters.responseFilters()) {
            if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
            try { fe.instance().filter(rctx, rctx2); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        return writeFromContext(rctx2, route, chosen);
    }

    private Response writeFromContext(CassiniResponseContext rctx, ResourceMethod route, MediaType chosen) throws IOException {
        Object entity = rctx.getEntity();
        int status = rctx.getStatus();
        MultivaluedMap<String, Object> headers = rctx.getHeaders();
        if (entity == null) {
            var b = Response.builder().status(StatusCode.of(status)).body(Body.empty());
            for (var e : headers.entrySet()) for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
            return b.build();
        }
        MediaType mt = rctx.getMediaType() != null ? rctx.getMediaType() : chosen;
        // Strip Content-Type from headers map (re-added by writeEntity)
        java.util.Map<String, java.util.List<String>> extra = new java.util.LinkedHashMap<>();
        for (var e : headers.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            java.util.List<String> vs = new java.util.ArrayList<>();
            for (Object v : e.getValue()) vs.add(String.valueOf(v));
            extra.put(e.getKey(), vs);
        }
        return writeEntity(entity, rctx.getEntityType() == null ? entity.getClass() : rctx.getEntityType(),
                rctx.getEntityAnnotations(), mt, StatusCode.of(status), extra);
    }

    private boolean hasRequestBody(Request request) {
        Body b = request.body();
        if (b == null) return false;
        long len = b.contentLength();
        if (len > 0) return true;
        // chunked encoding → contentLength may be -1 ; rely on presence of Content-Type
        return request.headers().contains("Content-Type") && len != 0;
    }

    private Object readEntity(Parameter p, MediaType ct, Request request) throws IOException {
        Class<?> type = p.getType();
        Type genericType = p.getParameterizedType();
        Annotation[] anns = p.getAnnotations();
        MultivaluedMap<String, String> headers = MessageBodyRegistry.adaptHeaders(request.headers());

        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyReader reader = registry.findReader(type, genericType, anns, ct)
                .orElseThrow(() -> new WebApplicationException(
                        "No MessageBodyReader for " + type.getName() + " / " + MediaTypes.format(ct), 415));
        try (InputStream in = request.body().asInputStream()) {
            if (filters.readerInterceptors().isEmpty()) {
                return reader.readFrom(type, genericType, anns, ct, headers, in);
            }
            return new CassiniReaderInterceptorContext(filters.readerInterceptors(),
                    reader, type, genericType, anns, ct, headers, in).proceed();
        }
    }

    private Response marshal(Object result, ResourceMethod route, MediaType chosen) throws IOException {
        if (result == null) {
            return Response.of(StatusCode.NO_CONTENT);
        }
        if (result instanceof jakarta.ws.rs.core.Response jr) {
            return fromJaxRs(jr, route, chosen);
        }
        return writeEntity(result, route.javaMethod().getGenericReturnType(),
                route.javaMethod().getAnnotations(), chosen, StatusCode.OK, Map.of());
    }

    private Response writeEntity(Object entity, Type genericType, Annotation[] anns,
                                 MediaType chosen, StatusCode status,
                                 Map<String, List<String>> extraHeaders) throws IOException {
        Class<?> type = entity.getClass();
        final MediaType mt = defaultFor(chosen, type);
        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyWriter writer = registry.findWriter(type, genericType, anns, mt)
                .orElseThrow(() -> new WebApplicationException(
                        "No MessageBodyWriter for " + type.getName() + " / " + MediaTypes.format(mt), 500));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MultivaluedMap<String, Object> outHeaders = MessageBodyRegistry.outHeaders();
        // Peupler outHeaders avec les extraHeaders AVANT le chain interceptor
        // pour que WriterInterceptor.aroundWriteTo.getHeaders() voie ce que
        // la ResourceMethod/ResponseBuilder a produit. Les interceptors
        // peuvent encore muter ; on relit ensuite pour build.
        for (var e : extraHeaders.entrySet())
            for (String v : e.getValue()) outHeaders.add(e.getKey(), v);
        if (filters.writerInterceptors().isEmpty()) {
            MessageBodyRegistry.writeTo(writer, entity, type, genericType, anns, mt, outHeaders, bos);
        } else {
            new CassiniWriterInterceptorContext(filters.writerInterceptors(), writer,
                    entity, type, genericType, anns, mt, outHeaders, bos).proceed();
        }

        var b = Response.builder().status(status).body(Body.of(bos.toByteArray()));
        b.header("Content-Type", MediaTypes.format(mt));
        for (var e : outHeaders.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
        }
        return b.build();
    }

    private Response fromJaxRs(jakarta.ws.rs.core.Response jr, ResourceMethod route,
                               MediaType fallback) throws IOException {
        StatusCode status = StatusCode.of(jr.getStatus());
        Object entity = jr.getEntity();
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : jr.getStringHeaders().entrySet()) {
            if (!"Content-Type".equalsIgnoreCase(e.getKey())) {
                headers.put(e.getKey(), e.getValue());
            }
        }
        MediaType chosen = jr.getMediaType() != null ? jr.getMediaType() : fallback;

        if (entity == null) {
            var b = Response.builder().status(status).body(Body.empty());
            for (var e : headers.entrySet()) for (String v : e.getValue()) b.header(e.getKey(), v);
            return b.build();
        }
        return writeEntity(entity, route.javaMethod().getGenericReturnType(),
                route.javaMethod().getAnnotations(), chosen, status, headers);
    }

    /**
     * Si {@code chosen} est un wildcard (ex. {@literal *}{@literal /}{@literal *}
     * issu d'un Accept implicite) et que le type entity a un Content-Type
     * naturel, on le substitue. Sinon on respecte le négocié.
     */
    private static MediaType defaultFor(MediaType chosen, Class<?> entityType) {
        if (!chosen.isWildcardType()) return chosen;
        if (CharSequence.class.isAssignableFrom(entityType)) return MediaType.TEXT_PLAIN_TYPE;
        if (byte[].class == entityType || InputStream.class.isAssignableFrom(entityType)
                || java.io.File.class.isAssignableFrom(entityType)) {
            return MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return chosen;
    }

    /** §3.7.2 : parmi les candidats (même path+verb), choisir celui dont
     *  @Consumes matche Content-Type ET @Produces matche Accept (spécificité
     *  maximale). Si aucun ne matche, retourne le premier (l'Invoker remontera
     *  415 ou 406 plus tard). */
    private MatchResult pickBestMatch(java.util.List<MatchResult> candidates, Request request) {
        if (candidates.size() == 1) return candidates.get(0);
        MediaType ct = MediaTypes.parse(request.headers().firstOrNull("Content-Type"));
        java.util.List<MediaType> accepts = MediaTypes.parseList(request.headers().firstOrNull("Accept"));
        MatchResult best = null;
        int bestScore = -1;
        for (MatchResult c : candidates) {
            var cons = MediaTypes.fromSet(c.method().consumes());
            if (hasRequestBody(request) && !cons.isEmpty() && !MediaTypes.consumesMatches(ct, cons)) continue;
            var prod = MediaTypes.fromSet(c.method().produces());
            int score = 0;
            if (!prod.isEmpty()) {
                var pick = MediaTypes.pickProduced(accepts, prod);
                if (pick.isEmpty()) continue;
                // Score : exact match > wildcard subtype > wildcard type
                MediaType p = pick.get();
                if (!p.isWildcardType() && !p.isWildcardSubtype()) score = 3;
                else if (!p.isWildcardType()) score = 2;
                else score = 1;
            }
            if (score > bestScore) { best = c; bestScore = score; }
        }
        return best != null ? best : candidates.get(0);
    }

    private Response renderWebAppException(WebApplicationException wae, ResourceMethod route,
                                           MediaType chosen, CassiniRequestContext rctx) throws IOException {
        // §4.4 : si un ExceptionMapper est enregistré pour WebApplicationException
        // ou un de ses super-types, il doit être appelé en priorité sur la
        // réponse embarquée dans l'exception.
        var mapped = exceptionMappers.map(wae);
        if (mapped.isPresent()) {
            jakarta.ws.rs.core.Response r = mapped.get();
            if (rctx != null && !filters.responseFilters().isEmpty())
                return runResponseFiltersAndWrite(rctx, r, route, chosen);
            return fromJaxRs(r, route, chosen);
        }
        jakarta.ws.rs.core.Response r = wae.getResponse();
        if (r != null) {
            if (rctx != null && !filters.responseFilters().isEmpty())
                return runResponseFiltersAndWrite(rctx, r, route, chosen);
            return fromJaxRs(r, route, chosen);
        }
        String msg = wae.getMessage() == null ? "" : wae.getMessage();
        return Response.builder()
                .status(StatusCode.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "text/plain;charset=utf-8")
                .body(Body.of(msg))
                .build();
    }
}
