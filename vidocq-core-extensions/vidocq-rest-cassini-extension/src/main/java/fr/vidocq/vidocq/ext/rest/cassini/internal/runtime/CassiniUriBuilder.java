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

    public CassiniUriBuilder() {}

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

    @Override @SuppressWarnings("rawtypes") public UriBuilder path(Class resource) { return this; }
    @Override @SuppressWarnings("rawtypes") public UriBuilder path(Class resource, String method) { return this; }
    @Override public UriBuilder path(java.lang.reflect.Method method) { return this; }

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

    @Override public UriBuilder resolveTemplate(String name, Object value) { return this; }
    @Override public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) { return this; }
    @Override public UriBuilder resolveTemplateFromEncoded(String name, Object value) { return this; }
    @Override public UriBuilder resolveTemplates(Map<String, Object> templateValues) { return this; }
    @Override public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) { return this; }
    @Override public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) { return this; }

    @Override public URI buildFromMap(Map<String, ?> values) { return build(); }
    @Override public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) { return build(); }
    @Override public URI buildFromEncodedMap(Map<String, ?> values) { return build(); }

    @Override public URI build(Object... values) {
        try {
            StringBuilder q = new StringBuilder();
            for (var e : query.entrySet()) {
                for (String v : e.getValue()) {
                    if (q.length() > 0) q.append('&');
                    q.append(e.getKey()).append('=').append(v);
                }
            }
            return new URI(scheme, userInfo, host, port, path.toString(),
                    q.length() == 0 ? null : q.toString(), fragment);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public URI build(Object[] values, boolean encodeSlashInPath) { return build(values); }
    @Override public URI buildFromEncoded(Object... values) { return build(values); }

    @Override public String toTemplate() {
        URI u = build();
        return u == null ? "" : u.toString();
    }
}
