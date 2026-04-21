package fr.vidocq.vidocq.ext.rest.cassini.internal.runtime;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implémentation minimaliste de {@link UriBuilder}. Couvre scheme/host/port/
 * path/queryParam/fragment. Les opérations {@code resolveTemplate*},
 * {@code matrixParam}, les Link-builders et builders à partir d'une
 * ressource annotée ne sont pas encore implémentés.
 */
public final class CassiniUriBuilder extends UriBuilder {

    private String scheme;
    private String userInfo;
    private String host;
    private int port = -1;
    private StringBuilder path = new StringBuilder();
    private final Map<String, List<String>> query = new LinkedHashMap<>();
    private String fragment;
    private final Map<String, Object> resolvedTemplates = new LinkedHashMap<>();

    public CassiniUriBuilder() {}

    public static UriBuilder fromResource(Class<?> resource) {
        if (resource == null) throw new IllegalArgumentException("resource is null");
        CassiniUriBuilder b = new CassiniUriBuilder();
        jakarta.ws.rs.Path p = resource.getAnnotation(jakarta.ws.rs.Path.class);
        if (p == null) throw new IllegalArgumentException("resource not @Path annotated: " + resource.getName());
        b.path.append(p.value());
        return b;
    }

    public static UriBuilder fromUri(URI uri) {
        CassiniUriBuilder b = new CassiniUriBuilder();
        b.scheme = uri.getScheme();
        b.userInfo = uri.getUserInfo();
        b.host = uri.getHost();
        b.port = uri.getPort();
        if (uri.getRawPath() != null) b.path.append(uri.getRawPath());
        b.fragment = uri.getFragment();
        return b;
    }

    @Override public UriBuilder clone() {
        CassiniUriBuilder b = new CassiniUriBuilder();
        b.scheme = scheme;
        b.userInfo = userInfo;
        b.host = host;
        b.port = port;
        b.path = new StringBuilder(path);
        b.query.putAll(query);
        b.fragment = fragment;
        return b;
    }

    @Override public UriBuilder uri(URI uri) {
        if (uri.getScheme() != null) scheme = uri.getScheme();
        if (uri.getUserInfo() != null) userInfo = uri.getUserInfo();
        if (uri.getHost() != null) host = uri.getHost();
        if (uri.getPort() != -1) port = uri.getPort();
        if (uri.getRawPath() != null) { path.setLength(0); path.append(uri.getRawPath()); }
        if (uri.getFragment() != null) fragment = uri.getFragment();
        return this;
    }

    @Override public UriBuilder uri(String uriTemplate) { return uri(URI.create(uriTemplate)); }

    @Override public UriBuilder scheme(String scheme) { this.scheme = scheme; return this; }

    @Override public UriBuilder schemeSpecificPart(String ssp) { return this; }

    @Override public UriBuilder userInfo(String ui) { this.userInfo = ui; return this; }

    @Override public UriBuilder host(String host) { this.host = host; return this; }

    @Override public UriBuilder port(int port) { this.port = port; return this; }

    @Override public UriBuilder replacePath(String p) { this.path = new StringBuilder(p == null ? "" : p); return this; }

    @Override public UriBuilder path(String segment) {
        if (segment == null || segment.isEmpty()) return this;
        if (path.length() > 0 && path.charAt(path.length() - 1) != '/' && !segment.startsWith("/")) path.append('/');
        path.append(segment);
        return this;
    }

    @Override @SuppressWarnings("rawtypes") public UriBuilder path(Class resource) {
        if (resource != null) {
            jakarta.ws.rs.Path p = (jakarta.ws.rs.Path) resource.getAnnotation(jakarta.ws.rs.Path.class);
            if (p != null) path(p.value());
        }
        return this;
    }
    @Override @SuppressWarnings({"rawtypes","unchecked"}) public UriBuilder path(Class resource, String method) {
        if (resource == null || method == null) return this;
        for (java.lang.reflect.Method m : resource.getMethods()) {
            if (!m.getName().equals(method)) continue;
            jakarta.ws.rs.Path p = m.getAnnotation(jakarta.ws.rs.Path.class);
            if (p != null) { path(p.value()); return this; }
        }
        return this;
    }
    @Override public UriBuilder path(java.lang.reflect.Method method) {
        if (method == null) return this;
        jakarta.ws.rs.Path p = method.getAnnotation(jakarta.ws.rs.Path.class);
        if (p != null) path(p.value());
        return this;
    }

    @Override public UriBuilder segment(String... segments) {
        for (String s : segments) path(s);
        return this;
    }

    @Override public UriBuilder replaceMatrix(String m) { return this; }
    @Override public UriBuilder matrixParam(String name, Object... values) { return this; }
    @Override public UriBuilder replaceMatrixParam(String name, Object... values) { return this; }

    @Override public UriBuilder replaceQuery(String q) {
        query.clear();
        if (q == null || q.isEmpty()) return this;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            query.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return this;
    }

    @Override public UriBuilder queryParam(String name, Object... values) {
        for (Object v : values) query.computeIfAbsent(name, k -> new ArrayList<>()).add(String.valueOf(v));
        return this;
    }

    @Override public UriBuilder replaceQueryParam(String name, Object... values) {
        query.remove(name);
        if (values != null && values.length > 0) queryParam(name, values);
        return this;
    }

    @Override public UriBuilder fragment(String f) { this.fragment = f; return this; }

    @Override public UriBuilder resolveTemplate(String name, Object value) {
        if (name == null) throw new IllegalArgumentException("name is null");
        if (value == null) throw new IllegalArgumentException("value is null");
        resolvedTemplates.put(name, value); return this;
    }
    @Override public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        return resolveTemplate(name, value);
    }
    @Override public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        return resolveTemplate(name, value);
    }
    @Override public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
        if (templateValues == null) throw new IllegalArgumentException("templateValues is null");
        for (var e : templateValues.entrySet()) resolveTemplate(e.getKey(), e.getValue());
        return this;
    }
    @Override public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) {
        return resolveTemplates(templateValues);
    }
    @Override public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        return resolveTemplates(templateValues);
    }

    @Override public URI buildFromMap(Map<String, ?> values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(null, values, true);
    }
    @Override public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(null, values, encodeSlashInPath);
    }
    @Override public URI buildFromEncodedMap(Map<String, ?> values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(null, values, true);
    }

    @Override public URI build(Object... values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(values, null, true);
    }
    @Override public URI build(Object[] values, boolean encodeSlashInPath) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(values, null, encodeSlashInPath);
    }
    @Override public URI buildFromEncoded(Object... values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(values, null, true);
    }

    private URI buildInternal(Object[] values, Map<String, ?> valueMap, boolean encodeSlash) {
        String substituted = substituteTemplates(path.toString(), values, valueMap);
        try {
            StringBuilder q = new StringBuilder();
            for (var e : query.entrySet()) {
                for (String v : e.getValue()) {
                    String resolved = substituteTemplates(v, values, valueMap);
                    if (q.length() > 0) q.append('&');
                    q.append(e.getKey()).append('=').append(resolved);
                }
            }
            return new URI(scheme, userInfo, host, port, substituted,
                    q.length() == 0 ? null : q.toString(),
                    substituteTemplates(fragment, values, valueMap));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private String substituteTemplates(String tpl, Object[] values, Map<String, ?> valueMap) {
        if (tpl == null || tpl.isEmpty()) return tpl;
        StringBuilder out = new StringBuilder();
        int i = 0;
        int posIdx = 0;
        while (i < tpl.length()) {
            char c = tpl.charAt(i);
            if (c == '{') {
                int end = tpl.indexOf('}', i);
                if (end < 0) { out.append(tpl, i, tpl.length()); break; }
                String inside = tpl.substring(i + 1, end).trim();
                int colon = inside.indexOf(':');
                String name = colon < 0 ? inside : inside.substring(0, colon).trim();
                Object val = null;
                if (resolvedTemplates.containsKey(name)) val = resolvedTemplates.get(name);
                else if (valueMap != null && valueMap.containsKey(name)) val = valueMap.get(name);
                else if (values != null && posIdx < values.length) val = values[posIdx++];
                if (val == null) {
                    out.append('{').append(inside).append('}');
                } else {
                    out.append(String.valueOf(val));
                }
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    @Override public String toTemplate() {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) sb.append(scheme).append(':');
        if (host != null) { sb.append("//"); if (userInfo != null) sb.append(userInfo).append('@');
                            sb.append(host); if (port >= 0) sb.append(':').append(port); }
        sb.append(path);
        if (!query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (var e : query.entrySet())
                for (String v : e.getValue()) { if (!first) sb.append('&'); first = false;
                                                sb.append(e.getKey()).append('=').append(v); }
        }
        if (fragment != null) sb.append('#').append(fragment);
        return sb.toString();
    }
}
