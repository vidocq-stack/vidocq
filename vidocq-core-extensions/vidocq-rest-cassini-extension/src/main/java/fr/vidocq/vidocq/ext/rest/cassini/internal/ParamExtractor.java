package fr.vidocq.vidocq.ext.rest.cassini.internal;

import fr.vidocq.chappe.api.Request;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniHttpHeaders;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniRequest;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniSecurityContext;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniUriInfo;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Résout les arguments d'appel d'une méthode de ressource à partir d'une
 * {@link Request} Chappe + d'un {@link MatchResult} routeur.
 *
 * <p>M2b : support de {@link PathParam}, {@link QueryParam}, {@link HeaderParam},
 * {@link CookieParam}, {@link FormParam}, {@link MatrixParam},
 * {@link DefaultValue}. Les paramètres sans annotation JAX-RS sont
 * considérés comme le corps de la requête — leur index est exposé via
 * {@link ResolvedArgs#bodyIndex} et sera rempli par l'Invoker via un
 * {@link MessageBodyRegistry}.</p>
 */
public final class ParamExtractor {

    public record ResolvedArgs(Object[] args, int bodyIndex) {}

    private ParamExtractor() {}

    public static ResolvedArgs resolve(ResourceMethod route, MatchResult match, Request request) {
        Parameter[] params = route.javaMethod().getParameters();
        Object[] args = new Object[params.length];
        int bodyIndex = -1;
        Map<String, List<String>> formCache = null;
        Map<String, List<String>> queryCache = null;

        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            String def = defaultValue(p);

            PathParam pathParam = p.getAnnotation(PathParam.class);
            QueryParam queryParam = p.getAnnotation(QueryParam.class);
            HeaderParam headerParam = p.getAnnotation(HeaderParam.class);
            CookieParam cookieParam = p.getAnnotation(CookieParam.class);
            FormParam formParam = p.getAnnotation(FormParam.class);
            MatrixParam matrixParam = p.getAnnotation(MatrixParam.class);
            Context context = p.getAnnotation(Context.class);
            BeanParam beanParam = p.getAnnotation(BeanParam.class);

            if (context != null) {
                args[i] = resolveContext(p.getType(), match, request);
                continue;
            }
            if (beanParam != null) {
                args[i] = instantiateBeanParam(p.getType(), match, request);
                continue;
            }
            if (pathParam != null) {
                String raw = match.pathParams().get(pathParam.value());
                args[i] = coerce(p, raw == null ? emptyOrDefault(def) : List.of(raw));
            } else if (queryParam != null) {
                if (queryCache == null) queryCache = parseQuery(request.query());
                List<String> raws = queryCache.getOrDefault(queryParam.value(), List.of());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (headerParam != null) {
                List<String> raws = request.headers().all(headerParam.value());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (cookieParam != null) {
                String raw = cookie(request, cookieParam.value());
                args[i] = coerce(p, raw == null ? emptyOrDefault(def) : List.of(raw));
            } else if (formParam != null) {
                if (formCache == null) formCache = readForm(request);
                List<String> raws = formCache.getOrDefault(formParam.value(), List.of());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (matrixParam != null) {
                List<String> raws = matrix(request, matrixParam.value());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (isBodyCandidate(p)) {
                if (bodyIndex < 0) bodyIndex = i;
                args[i] = ParamValueConverter.defaultForType(p.getType());
            } else {
                args[i] = ParamValueConverter.defaultForType(p.getType());
            }
        }
        return new ResolvedArgs(args, bodyIndex);
    }

    /** Vrai si le paramètre n'a aucune annotation JAX-RS reconnue → candidat body. */
    private static boolean isBodyCandidate(Parameter p) {
        for (Annotation a : p.getAnnotations()) {
            Class<? extends Annotation> t = a.annotationType();
            if (t == PathParam.class || t == QueryParam.class || t == HeaderParam.class
                    || t == CookieParam.class || t == FormParam.class || t == MatrixParam.class
                    || t == DefaultValue.class || t == Context.class || t == BeanParam.class) {
                return false;
            }
            if (t.getName().startsWith("jakarta.ws.rs.")) return false;
        }
        return true;
    }

    private static Object instantiateBeanParam(Class<?> type, MatchResult match, Request request) {
        try {
            Object instance = type.getDeclaredConstructor().newInstance();
            FieldInjector.inject(instance, match, request);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new WebApplicationException("Failed to instantiate @BeanParam "
                    + type.getName() + ": " + e.getMessage(), 500);
        }
    }

    private static Object resolveContext(Class<?> type, MatchResult match, Request request) {
        if (type == UriInfo.class) return new CassiniUriInfo(request, request.contextPath(), match.pathParams());
        if (type == HttpHeaders.class) return new CassiniHttpHeaders(request);
        if (type == jakarta.ws.rs.core.Request.class) return new CassiniRequest(request.method().name());
        if (type == SecurityContext.class) return new CassiniSecurityContext(request);
        if (type == Request.class) return request; // Chappe Request passthrough (utile pour tests)
        throw new WebApplicationException("Unsupported @Context type: " + type.getName(), 500);
    }

    private static Object coerce(Parameter p, List<String> raws) {
        Class<?> raw = p.getType();
        Class<?> element = ParamValueConverter.isListLike(raw)
                ? genericElementType(p.getParameterizedType())
                : raw;
        try {
            return ParamValueConverter.coerce(raw, element, raws);
        } catch (WebApplicationException w) {
            throw w;
        } catch (RuntimeException e) {
            // §3.2 : @PathParam/@MatrixParam → 404, autres → 400
            int status = (p.getAnnotation(PathParam.class) != null
                    || p.getAnnotation(MatrixParam.class) != null) ? 404 : 400;
            throw new WebApplicationException("Invalid value for parameter "
                    + p.getName() + ": " + e.getMessage(), status);
        }
    }

    private static List<String> emptyOrDefault(String def) {
        return def == null ? List.of() : List.of(def);
    }

    private static String defaultValue(Parameter p) {
        DefaultValue d = p.getAnnotation(DefaultValue.class);
        return d == null ? null : d.value();
    }

    private static Class<?> genericElementType(Type t) {
        if (t instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return String.class;
    }

    private static Map<String, List<String>> parseQuery(String raw) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        return FormDecoder.parse(raw);
    }

    private static String cookie(Request request, String name) {
        for (String header : request.headers().all("Cookie")) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String n = pair.substring(0, eq).trim();
                if (n.equals(name)) {
                    String v = pair.substring(eq + 1).trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v;
                }
            }
        }
        return null;
    }

    private static List<String> matrix(Request request, String name) {
        List<String> out = new ArrayList<>();
        String path = request.pathInfo();
        if (path == null) return out;
        for (String seg : path.split("/")) {
            int semi = seg.indexOf(';');
            if (semi < 0) continue;
            for (String pair : seg.substring(semi + 1).split(";")) {
                int eq = pair.indexOf('=');
                String n = eq < 0 ? pair : pair.substring(0, eq);
                if (URLDecoder.decode(n, StandardCharsets.UTF_8).equals(name)) {
                    out.add(eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static Map<String, List<String>> readForm(Request request) {
        try {
            var body = request.body();
            if (body == null || body.contentLength() == 0) return new LinkedHashMap<>();
            byte[] bytes = body.asInputStream().readAllBytes();
            return FormDecoder.decode(bytes);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to read form body: " + e.getMessage(), 400);
        }
    }
}
