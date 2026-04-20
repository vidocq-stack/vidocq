package fr.vidocq.vidocq.ext.rest.cassini.internal;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
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

    private final Function<Class<?>, Object> resolver;
    private final MessageBodyRegistry registry;
    private final ExceptionMapperRegistry exceptionMappers;

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
        ResourceMethod route = match.method();

        // 1. Negotiation
        MediaType contentType = MediaTypes.parse(request.headers().firstOrNull("Content-Type"));
        List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        if (hasRequestBody(request) && !MediaTypes.consumesMatches(contentType, consumes)) {
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
        ParamExtractor.ResolvedArgs resolved = ParamExtractor.resolve(route, match, request);
        Object[] args = resolved.args();
        if (resolved.bodyIndex() >= 0) {
            Parameter p = route.javaMethod().getParameters()[resolved.bodyIndex()];
            args[resolved.bodyIndex()] = readEntity(p, contentType, request);
        }

        // 3. Invoke
        Object target = resolver.apply(route.beanClass());
        Object result;
        try {
            result = route.javaMethod().invoke(target, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            var mapped = exceptionMappers.map(cause);
            if (mapped.isPresent()) return fromJaxRs(mapped.get(), route, chosen);
            if (cause instanceof WebApplicationException wae) {
                return renderWebAppException(wae, route, chosen);
            }
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }

        // 4. Marshal
        return marshal(result, route, chosen);
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
            return reader.readFrom(type, genericType, anns, ct, headers, in);
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
        MessageBodyRegistry.writeTo(writer, entity, type, genericType, anns, mt, outHeaders, bos);

        var b = Response.builder().status(status).body(Body.of(bos.toByteArray()));
        b.header("Content-Type", MediaTypes.format(mt));
        for (var e : extraHeaders.entrySet()) for (String v : e.getValue()) b.header(e.getKey(), v);
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

    private Response renderWebAppException(WebApplicationException wae, ResourceMethod route,
                                           MediaType chosen) throws IOException {
        jakarta.ws.rs.core.Response r = wae.getResponse();
        if (r != null) return fromJaxRs(r, route, chosen);
        String msg = wae.getMessage() == null ? "" : wae.getMessage();
        return Response.builder()
                .status(StatusCode.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "text/plain;charset=utf-8")
                .body(Body.of(msg))
                .build();
    }
}
