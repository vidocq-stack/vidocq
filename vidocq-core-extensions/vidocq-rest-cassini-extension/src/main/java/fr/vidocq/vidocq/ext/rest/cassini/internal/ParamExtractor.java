package fr.vidocq.vidocq.ext.rest.cassini.internal;

import fr.vidocq.chappe.api.Request;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;

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
 * {@link DefaultValue}. Valeurs simples ou {@code List/Set/SortedSet}.</p>
 */
public final class ParamExtractor {

    private ParamExtractor() {}

    public static Object[] resolve(ResourceMethod route, MatchResult match, Request request) {
        Parameter[] params = route.javaMethod().getParameters();
        Object[] args = new Object[params.length];
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
            } else {
                args[i] = ParamValueConverter.defaultForType(p.getType());
            }
        }
        return args;
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
            throw new WebApplicationException("Invalid value for parameter "
                    + p.getName() + ": " + e.getMessage(), 400);
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
