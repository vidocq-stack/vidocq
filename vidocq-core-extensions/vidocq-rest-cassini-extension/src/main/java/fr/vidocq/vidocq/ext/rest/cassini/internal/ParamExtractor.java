package fr.vidocq.vidocq.ext.rest.cassini.internal;

import fr.vidocq.chappe.api.Request;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniHttpHeaders;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniProviders;
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
import jakarta.ws.rs.ext.Providers;

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

    private static ThreadLocal<Providers> CURRENT_PROVIDERS = new ThreadLocal<>();

    /** Permet à l'Invoker d'exposer un Providers au resolveContext pour la durée d'une requête. */
    public static void setProviders(Providers p) { CURRENT_PROVIDERS.set(p); }
    public static void clearProviders() { CURRENT_PROVIDERS.remove(); }
    public static Providers currentProviders() { return CURRENT_PROVIDERS.get(); }

    private ParamExtractor() {}

    /**
     * Résout les arguments d'un constructeur de ressource (§3.1.1).
     * Les paramètres sans annotation JAX-RS reçoivent leur valeur par défaut
     * (un constructeur ne peut pas consommer le body).
     */
    public static Object[] resolveConstructorArgs(Parameter[] params, MatchResult match, Request request) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = resolveInjectedParam(params[i], match, request);
        }
        return args;
    }

    private static Object resolveInjectedParam(Parameter p, MatchResult match, Request request) {
        String def = defaultValue(p);
        boolean encoded = p.getAnnotation(jakarta.ws.rs.Encoded.class) != null;
        Context context = p.getAnnotation(Context.class);
        if (context != null) return resolveContext(p.getType(), match, request);

        BeanParam beanParam = p.getAnnotation(BeanParam.class);
        if (beanParam != null) return instantiateBeanParam(p.getType(), match, request);

        PathParam pathParam = p.getAnnotation(PathParam.class);
        if (pathParam != null) {
            String raw = match.pathParams().get(pathParam.value());
            return coerce(p, raw == null ? emptyOrDefault(def) : List.of(raw));
        }
        QueryParam queryParam = p.getAnnotation(QueryParam.class);
        if (queryParam != null) {
            List<String> raws = parsedQueryFromRequest(request, encoded).getOrDefault(queryParam.value(), List.of());
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        HeaderParam headerParam = p.getAnnotation(HeaderParam.class);
        if (headerParam != null) {
            List<String> raws = request.headers().all(headerParam.value());
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        CookieParam cookieParam = p.getAnnotation(CookieParam.class);
        if (cookieParam != null) {
            String raw = cookie(request, cookieParam.value());
            return coerce(p, raw == null ? emptyOrDefault(def) : List.of(raw));
        }
        MatrixParam matrixParam = p.getAnnotation(MatrixParam.class);
        if (matrixParam != null) {
            List<String> raws = matrix(request, matrixParam.value(), encoded);
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        FormParam formParam = p.getAnnotation(FormParam.class);
        if (formParam != null) {
            List<String> raws = readForm(request, encoded).getOrDefault(formParam.value(), List.of());
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        return ParamValueConverter.defaultForType(p.getType());
    }

    public static ResolvedArgs resolve(ResourceMethod route, MatchResult match, Request request) {
        Parameter[] params = route.javaMethod().getParameters();
        Object[] args = new Object[params.length];
        int bodyIndex = -1;
        Map<String, List<String>> formCache = null;
        Map<String, List<String>> queryCache = null;
        Map<String, List<String>> formCacheEncoded = null;
        Map<String, List<String>> queryCacheEncoded = null;
        boolean methodEncoded = route.javaMethod().getAnnotation(jakarta.ws.rs.Encoded.class) != null
                || route.beanClass().getAnnotation(jakarta.ws.rs.Encoded.class) != null;

        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            String def = defaultValue(p);
            boolean encoded = methodEncoded || p.getAnnotation(jakarta.ws.rs.Encoded.class) != null;

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
                Map<String, List<String>> cache;
                if (encoded) {
                    if (queryCacheEncoded == null) queryCacheEncoded = parsedQueryFromRequest(request, true);
                    cache = queryCacheEncoded;
                } else {
                    if (queryCache == null) queryCache = parsedQueryFromRequest(request, false);
                    cache = queryCache;
                }
                List<String> raws = cache.getOrDefault(queryParam.value(), List.of());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (headerParam != null) {
                List<String> raws = request.headers().all(headerParam.value());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (cookieParam != null) {
                String raw = cookie(request, cookieParam.value());
                args[i] = coerce(p, raw == null ? emptyOrDefault(def) : List.of(raw));
            } else if (formParam != null) {
                Map<String, List<String>> cache;
                if (encoded) {
                    if (formCacheEncoded == null) formCacheEncoded = readForm(request, true);
                    cache = formCacheEncoded;
                } else {
                    if (formCache == null) formCache = readForm(request, false);
                    cache = formCache;
                }
                List<String> raws = cache.getOrDefault(formParam.value(), List.of());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (matrixParam != null) {
                List<String> raws = matrix(request, matrixParam.value(), encoded);
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
        if (type == jakarta.ws.rs.core.Request.class) return new CassiniRequest(request);
        if (type == SecurityContext.class) return new CassiniSecurityContext(request);
        if (type == Providers.class) {
            Providers p = CURRENT_PROVIDERS.get();
            if (p != null) return p;
        }
        if (type == jakarta.ws.rs.core.Application.class) {
            // §9.4 : Application est l'instance de l'Application JAX-RS.
            // On fournit une instance minimale (pas d'Application user-level
            // configurée côté harness).
            return new jakarta.ws.rs.core.Application();
        }
        if (type == jakarta.ws.rs.ext.ContextResolver.class) {
            Providers p = CURRENT_PROVIDERS.get();
            if (p != null) return p;
        }
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

    private static Map<String, List<String>> parseQuery(String raw, boolean encoded) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        return FormDecoder.parse(raw, !encoded);
    }

    private static Map<String, List<String>> parsedQueryFromRequest(Request request, boolean encoded) {
        String q = request.query();
        if (q == null || q.isEmpty()) {
            java.net.URI u = request.uri();
            if (u != null) {
                String raw = u.getRawQuery();
                if (raw != null && !raw.isEmpty()) q = raw;
            }
        }
        return parseQuery(q, encoded);
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

    private static List<String> matrix(Request request, String name, boolean encoded) {
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
                    if (eq < 0) out.add("");
                    else {
                        String v = pair.substring(eq + 1);
                        out.add(encoded ? v : URLDecoder.decode(v, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, List<String>> readForm(Request request) {
        return readForm(request, false);
    }
    private static Map<String, List<String>> readForm(Request request, boolean encoded) {
        // On a deux caches distincts selon le mode (decoded vs @Encoded).
        if (encoded) {
            Map<String, List<String>> enc = FieldInjector.FORM_CACHE_ENCODED.get();
            if (enc != null) return enc;
        } else {
            Map<String, List<String>> cached = FieldInjector.FORM_CACHE.get();
            if (cached != null) return cached;
        }
        try {
            var body = request.body();
            byte[] bytes = (body == null || body.contentLength() == 0)
                    ? new byte[0] : body.asInputStream().readAllBytes();
            if (encoded) {
                String s = new String(bytes, StandardCharsets.UTF_8);
                Map<String, List<String>> parsed = FormDecoder.parse(s, false);
                FieldInjector.FORM_CACHE_ENCODED.set(parsed);
                return parsed;
            } else {
                Map<String, List<String>> parsed = bytes.length == 0
                        ? new LinkedHashMap<>() : FormDecoder.decode(bytes);
                FieldInjector.FORM_CACHE.set(parsed);
                return parsed;
            }
        } catch (Exception e) {
            throw new WebApplicationException("Failed to read form body: " + e.getMessage(), 400);
        }
    }
}
