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

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Injecte dans les champs d'une instance de ressource les valeurs
 * {@link PathParam}/{@link QueryParam}/{@link HeaderParam}/{@link CookieParam}/
 * {@link FormParam}/{@link MatrixParam} ainsi que les injections {@link Context}
 * (UriInfo, HttpHeaders, Request, SecurityContext) déclarées au niveau
 * champ (§3.2 / §9).
 *
 * <p>Pour chaque requête, on parcourt les champs déclarés et on set
 * leur valeur via reflection. Les instances JAX-RS étant typiquement
 * {@code @RequestScoped} (donc une nouvelle instance de backing par
 * requête côté CDI), cette ré-injection est sûre.</p>
 */
public final class FieldInjector {

    private FieldInjector() {}

    public static void inject(Object target, MatchResult match, Request request) {
        if (target == null) return;
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Object value = resolveFieldValue(f, match, request);
                if (value != null) setField(target, f, value);
            }
            cls = cls.getSuperclass();
        }
    }

    private static Object resolveFieldValue(Field f, MatchResult match, Request request) {
        Context ctx = f.getAnnotation(Context.class);
        if (ctx != null) return resolveContext(f.getType(), match, request);

        BeanParam bp = f.getAnnotation(BeanParam.class);
        if (bp != null) {
            try {
                Object nested = f.getType().getDeclaredConstructor().newInstance();
                inject(nested, match, request);
                return nested;
            } catch (ReflectiveOperationException e) {
                throw new WebApplicationException("Failed to instantiate @BeanParam field "
                        + f.getName() + ": " + e.getMessage(), 500);
            }
        }

        String def = defaultValue(f);
        boolean encoded = f.getAnnotation(jakarta.ws.rs.Encoded.class) != null
                || f.getDeclaringClass().getAnnotation(jakarta.ws.rs.Encoded.class) != null;
        PathParam pp = f.getAnnotation(PathParam.class);
        if (pp != null) {
            String raw = match.pathParams().get(pp.value());
            String val = raw == null ? null : (encoded ? raw : decodePath(raw));
            return coerce(f, val == null ? emptyOrDef(def) : List.of(val));
        }
        QueryParam qp = f.getAnnotation(QueryParam.class);
        if (qp != null) {
            List<String> raws = parsedQueryParams(request, encoded).getOrDefault(qp.value(), List.of());
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        HeaderParam hp = f.getAnnotation(HeaderParam.class);
        if (hp != null) {
            List<String> raws = request.headers().all(hp.value());
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        CookieParam cp = f.getAnnotation(CookieParam.class);
        if (cp != null) {
            String raw = cookie(request, cp.value());
            return coerce(f, raw == null ? emptyOrDef(def) : List.of(raw));
        }
        MatrixParam mp = f.getAnnotation(MatrixParam.class);
        if (mp != null) {
            List<String> raws = matrix(request, mp.value(), encoded);
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        FormParam fp = f.getAnnotation(FormParam.class);
        if (fp != null) {
            Map<String, List<String>> form = readForm(request, encoded);
            List<String> raws = form.getOrDefault(fp.value(), List.of());
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        return null;
    }

    private static Object resolveContext(Class<?> type, MatchResult match, Request request) {
        if (type == UriInfo.class) return new CassiniUriInfo(request, request.contextPath(), match.pathParams());
        if (type == HttpHeaders.class) return new CassiniHttpHeaders(request);
        if (type == jakarta.ws.rs.core.Request.class) return new CassiniRequest(request);
        if (type == SecurityContext.class) return new CassiniSecurityContext(request);
        if (type == jakarta.ws.rs.ext.Providers.class) return ParamExtractor.currentProviders();
        if (type == jakarta.ws.rs.core.Application.class) return new jakarta.ws.rs.core.Application();
        if (type == jakarta.ws.rs.container.ContainerRequestContext.class) {
            var rctx = new fr.vidocq.vidocq.ext.rest.cassini.internal.filter.CassiniRequestContext(
                    request, new CassiniUriInfo(request, request.contextPath(), match.pathParams()));
            rctx.markPostMatching();
            return rctx;
        }
        return null;
    }

    private static Object coerce(Field f, List<String> raws) {
        Class<?> raw = f.getType();
        Class<?> element = ParamValueConverter.isListLike(raw)
                ? genericElementType(f.getGenericType()) : raw;
        try { return ParamValueConverter.coerce(raw, element, raws); }
        catch (WebApplicationException wae) {
            if (f.getAnnotation(PathParam.class) != null
                    || f.getAnnotation(MatrixParam.class) != null
                    || f.getAnnotation(HeaderParam.class) != null
                    || f.getAnnotation(CookieParam.class) != null) throw wae;
            return null;
        }
        catch (RuntimeException e) {
            if (f.getAnnotation(PathParam.class) != null
                    || f.getAnnotation(MatrixParam.class) != null) {
                throw new WebApplicationException("Invalid value for field "
                        + f.getName() + ": " + e.getMessage(), 404);
            }
            if (f.getAnnotation(HeaderParam.class) != null
                    || f.getAnnotation(CookieParam.class) != null) {
                throw new WebApplicationException("Invalid value for field "
                        + f.getName() + ": " + e.getMessage(), 400);
            }
            return null;
        }
    }

    private static Class<?> genericElementType(Type t) {
        if (t instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) return c;
        return String.class;
    }

    private static void setField(Object target, Field f, Object v) {
        try { f.setAccessible(true); f.set(target, v); }
        catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }

    private static List<String> emptyOrDef(String def) { return def == null ? List.of() : List.of(def); }
    private static String defaultValue(Field f) {
        DefaultValue d = f.getAnnotation(DefaultValue.class);
        return d == null ? null : d.value();
    }

    private static String decodePath(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static Map<String, List<String>> parseQuery(String raw, boolean encoded) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        return FormDecoder.parse(raw, !encoded);
    }

    /** Résout la query depuis request.query() ou, si null, depuis request.uri().
     *  Chappe retourne parfois null pour query() quand le path a été rewrité
     *  par un handler intermédiaire (ContextStrippingHandler côté TCK harness). */
    private static Map<String, List<String>> parsedQueryParams(Request request, boolean encoded) {
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

    static final ThreadLocal<Map<String, List<String>>> FORM_CACHE = new ThreadLocal<>();
    static final ThreadLocal<Map<String, List<String>>> FORM_CACHE_ENCODED = new ThreadLocal<>();

    private static Map<String, List<String>> readForm(Request request, boolean encoded) {
        if (encoded) {
            Map<String, List<String>> enc = FORM_CACHE_ENCODED.get();
            if (enc != null) return enc;
        } else {
            Map<String, List<String>> cached = FORM_CACHE.get();
            if (cached != null) return cached;
        }
        try {
            var body = request.body();
            byte[] bytes = (body == null || body.contentLength() == 0)
                    ? new byte[0] : body.asInputStream().readAllBytes();
            if (encoded) {
                Map<String, List<String>> parsed = FormDecoder.parse(
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8), false);
                FORM_CACHE_ENCODED.set(parsed);
                return parsed;
            } else {
                Map<String, List<String>> parsed = bytes.length == 0
                        ? new LinkedHashMap<>() : FormDecoder.decode(bytes);
                FORM_CACHE.set(parsed);
                return parsed;
            }
        } catch (Exception e) {
            throw new WebApplicationException("Failed to read form body: " + e.getMessage(), 400);
        }
    }

    public static void clearFormCache() {
        FORM_CACHE.remove();
        FORM_CACHE_ENCODED.remove();
    }
}
