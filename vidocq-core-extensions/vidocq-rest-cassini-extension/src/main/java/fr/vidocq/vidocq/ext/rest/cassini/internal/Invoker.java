package fr.vidocq.vidocq.ext.rest.cassini.internal;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Exécute une {@link ResourceMethod} résolue : délègue l'instanciation du
 * bean à un resolver, invoque la méthode Java, convertit le retour en
 * {@link Response} Chappe.
 *
 * <p>M1 : aucun paramètre injecté (méthodes sans argument uniquement),
 * retour {@code String} → {@code text/plain}, retour
 * {@link jakarta.ws.rs.core.Response} → passage status/headers/entity, retour
 * {@code void} → 204 No Content. Les {@code @*Param} et les body readers
 * arrivent en M2b / M2c.</p>
 */
public final class Invoker {

    private static final String DEFAULT_CONTENT_TYPE = "text/plain;charset=utf-8";

    private final Function<Class<?>, Object> resolver;

    public Invoker(Function<Class<?>, Object> resolver) {
        this.resolver = resolver;
    }

    /** Fabrique un Invoker qui résout les beans via un {@link BeanManager} CDI. */
    public static Invoker forBeanManager(BeanManager bm) {
        AnnotationLiteral<Any> any = new AnnotationLiteral<Any>() {};
        return new Invoker(type -> {
            Set<Bean<?>> beans = bm.getBeans(type, any);
            Bean<?> bean = bm.resolve(beans);
            if (bean == null) throw new IllegalStateException("No CDI bean for " + type.getName());
            var cc = bm.createCreationalContext(bean);
            return bm.getReference(bean, type, cc);
        });
    }

    public Response invoke(MatchResult match, Request request) throws Exception {
        ResourceMethod route = match.method();
        Object target = resolver.apply(route.beanClass());
        Object[] args = ParamExtractor.resolve(route, match, request);
        Object result;
        try {
            result = route.javaMethod().invoke(target, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
        return marshal(result, route.produces());
    }

    private Response marshal(Object result, Set<String> produces) {
        if (result == null) {
            return Response.of(StatusCode.NO_CONTENT);
        }
        if (result instanceof jakarta.ws.rs.core.Response jr) {
            return fromJaxRs(jr);
        }
        String contentType = produces.isEmpty() ? DEFAULT_CONTENT_TYPE : produces.iterator().next();
        byte[] bytes = String.valueOf(result).getBytes(StandardCharsets.UTF_8);
        return Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", contentType)
                .body(Body.of(bytes))
                .build();
    }

    private Response fromJaxRs(jakarta.ws.rs.core.Response jr) {
        var builder = Response.builder().status(StatusCode.of(jr.getStatus()));
        for (Map.Entry<String, List<String>> e : jr.getStringHeaders().entrySet()) {
            for (String v : e.getValue()) {
                builder.header(e.getKey(), v);
            }
        }
        Object entity = jr.getEntity();
        if (entity == null) {
            builder.body(Body.empty());
        } else if (entity instanceof byte[] ba) {
            builder.body(Body.of(ba));
        } else {
            builder.body(Body.of(String.valueOf(entity).getBytes(StandardCharsets.UTF_8)));
        }
        return builder.build();
    }
}
