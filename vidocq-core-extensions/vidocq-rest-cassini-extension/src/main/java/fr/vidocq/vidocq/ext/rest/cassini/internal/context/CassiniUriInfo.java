package fr.vidocq.vidocq.ext.rest.cassini.internal.context;

import fr.vidocq.chappe.api.Request;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import fr.vidocq.vidocq.ext.rest.cassini.internal.runtime.CassiniUriBuilder;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Implémentation {@link UriInfo} adossée à une {@link Request} Chappe + aux
 * path parameters capturés par le routeur.
 *
 * <p>M2d : méthodes simples (getPath, getBase/RequestUri, get{Query,Path}Parameters)
 * fonctionnelles. Les méthodes {@code getAbsolutePathBuilder()} / {@code
 * get*Builder()} lèvent {@link UnsupportedOperationException} tant que la
 * {@code RuntimeDelegate} Cassini (M2e) n'est pas en place.</p>
 */
public final class CassiniUriInfo implements UriInfo {

    private final Request request;
    private final String contextPath;
    private final Map<String, String> pathParams;

    public CassiniUriInfo(Request request, String contextPath, Map<String, String> pathParams) {
        this.request = request;
        this.contextPath = contextPath == null ? "" : contextPath;
        this.pathParams = pathParams == null ? Map.of() : pathParams;
    }

    @Override public String getPath() {
        String p = request.pathInfo();
        if (p == null || p.isEmpty()) return "";
        return p.startsWith("/") ? p.substring(1) : p;
    }

    @Override public String getPath(boolean decode) { return getPath(); }

    @Override public List<PathSegment> getPathSegments() { return getPathSegments(true); }

    @Override public List<PathSegment> getPathSegments(boolean decode) {
        List<PathSegment> segs = new ArrayList<>();
        for (String s : getPath().split("/")) {
            if (s.isEmpty()) continue;
            segs.add(new SimpleSegment(s));
        }
        return Collections.unmodifiableList(segs);
    }

    @Override public URI getRequestUri() {
        URI u = request.uri();
        if (u != null && u.isAbsolute()) return u;
        return resolveAbsolute(u == null ? request.path() : u.toString());
    }

    private URI resolveAbsolute(String pathAndQuery) {
        try {
            String scheme = request.isSecure() ? "https" : "http";
            String host = request.headers().firstOrNull("Host");
            if (host == null || host.isEmpty()) host = "127.0.0.1";
            String pq = pathAndQuery == null ? "/" : pathAndQuery;
            if (!pq.startsWith("/")) pq = "/" + pq;
            return new URI(scheme + "://" + host + pq);
        } catch (Exception e) { return request.uri(); }
    }

    @Override public UriBuilder getRequestUriBuilder() {
        return CassiniUriBuilder.fromUri(getRequestUri());
    }

    @Override public URI getAbsolutePath() {
        URI req = getRequestUri();
        try {
            return new URI(req.getScheme(), req.getAuthority(), req.getPath(), null, null);
        } catch (Exception e) { return req; }
    }

    @Override public UriBuilder getAbsolutePathBuilder() {
        return CassiniUriBuilder.fromUri(getAbsolutePath());
    }

    @Override public URI getBaseUri() {
        URI req = getRequestUri();
        try {
            String base = contextPath.isEmpty() || "/".equals(contextPath) ? "/" : contextPath + "/";
            return new URI(req.getScheme(), req.getAuthority(), base, null, null);
        } catch (Exception e) { return req; }
    }

    @Override public UriBuilder getBaseUriBuilder() {
        return CassiniUriBuilder.fromUri(getBaseUri());
    }

    @Override public MultivaluedMap<String, String> getPathParameters() { return getPathParameters(true); }

    @Override public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        pathParams.forEach(m::add);
        return m;
    }

    @Override public MultivaluedMap<String, String> getQueryParameters() { return getQueryParameters(true); }

    @Override public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        request.queryParams().forEach(m::add);
        return m;
    }

    @Override public List<String> getMatchedURIs() { return List.of(); }

    @Override public List<String> getMatchedURIs(boolean decode) { return List.of(); }

    @Override public String getMatchedResourceTemplate() { return ""; }

    @Override public List<Object> getMatchedResources() { return List.of(); }

    @Override public URI resolve(URI uri) { return getBaseUri().resolve(uri); }

    @Override public URI relativize(URI uri) { return getRequestUri().relativize(uri); }

    private record SimpleSegment(String value) implements PathSegment {
        @Override public String getPath() { return value; }
        @Override public MultivaluedMap<String, String> getMatrixParameters() { return new MultivaluedHashMap<>(); }
    }
}
