package fr.vidocq.vidocq.ext.rest.cassini.internal.filter;

import fr.vidocq.chappe.api.Request;
import fr.vidocq.vidocq.ext.rest.cassini.internal.MediaTypes;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniHttpHeaders;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniSecurityContext;
import fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniUriInfo;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implémentation de {@link ContainerRequestContext} adossée à une
 * {@link Request} Chappe. Les filtres peuvent muter headers, entity stream,
 * method et aborter la requête via {@link #abortWith(Response)}.
 */
public final class CassiniRequestContext implements ContainerRequestContext {

    private final Request request;
    private final CassiniHttpHeaders httpHeaders;
    private final Map<String, Object> properties = new HashMap<>();
    private final MultivaluedMap<String, String> headers;
    private String method;
    private URI requestUri;
    private URI baseUri;
    private InputStream entityStream;
    private SecurityContext securityContext;
    private UriInfo uriInfo;
    private Response aborted;

    public CassiniRequestContext(Request request, UriInfo uriInfo) {
        this.request = request;
        this.httpHeaders = new CassiniHttpHeaders(request);
        this.headers = buildHeaders(request);
        this.method = request.method().name();
        this.requestUri = request.uri();
        this.baseUri = uriInfo.getBaseUri();
        this.entityStream = request.body() == null ? new ByteArrayInputStream(new byte[0])
                : request.body().asInputStream();
        this.securityContext = new CassiniSecurityContext(request);
        this.uriInfo = uriInfo;
    }

    public Request chappeRequest() { return request; }
    public InputStream currentEntityStream() { return entityStream; }
    public boolean isAborted() { return aborted != null; }
    public Response abortedResponse() { return aborted; }

    private static MultivaluedMap<String, String> buildHeaders(Request request) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : request.headers()) m.add(e.name(), e.value());
        return m;
    }

    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return Collections.unmodifiableSet(properties.keySet()); }
    @Override public void setProperty(String name, Object value) { properties.put(name, value); }
    @Override public void removeProperty(String name) { properties.remove(name); }

    @Override public UriInfo getUriInfo() { return uriInfo; }
    @Override public void setRequestUri(URI requestUri) { this.requestUri = requestUri; }
    @Override public void setRequestUri(URI baseUri, URI requestUri) { this.baseUri = baseUri; this.requestUri = requestUri; }

    @Override public jakarta.ws.rs.core.Request getRequest() {
        return new fr.vidocq.vidocq.ext.rest.cassini.internal.context.CassiniRequest(method);
    }

    @Override public String getMethod() { return method; }
    @Override public void setMethod(String method) { this.method = method; }

    @Override public MultivaluedMap<String, String> getHeaders() { return headers; }

    @Override public String getHeaderString(String name) {
        List<String> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        return String.join(",", v);
    }

    @Override public boolean containsHeaderString(String n, String sep, java.util.function.Predicate<String> p) {
        List<String> vs = headers.get(n);
        if (vs == null) return false;
        for (String v : vs) for (String tok : v.split(sep)) if (p.test(tok.trim())) return true;
        return false;
    }

    @Override public boolean containsHeaderString(String n, java.util.function.Predicate<String> p) {
        return containsHeaderString(n, ",", p);
    }

    @Override public Date getDate() { return httpHeaders.getDate(); }
    @Override public Locale getLanguage() { return httpHeaders.getLanguage(); }
    @Override public int getLength() { return httpHeaders.getLength(); }
    @Override public MediaType getMediaType() { return httpHeaders.getMediaType(); }
    @Override public List<MediaType> getAcceptableMediaTypes() { return httpHeaders.getAcceptableMediaTypes(); }
    @Override public List<Locale> getAcceptableLanguages() { return httpHeaders.getAcceptableLanguages(); }
    @Override public Map<String, Cookie> getCookies() { return httpHeaders.getCookies(); }

    @Override public boolean hasEntity() {
        return request.body() != null && request.body().contentLength() != 0;
    }

    @Override public InputStream getEntityStream() { return entityStream; }
    @Override public void setEntityStream(InputStream input) { this.entityStream = input; }

    @Override public SecurityContext getSecurityContext() { return securityContext; }
    @Override public void setSecurityContext(SecurityContext context) { this.securityContext = context; }

    @Override public void abortWith(Response response) { this.aborted = response; }

    public MediaType parsedContentType() {
        return MediaTypes.parse(getHeaderString("Content-Type"));
    }

    // Used by ParamExtractor / Invoker quand les headers ont été mutés.
    public static CassiniRequestContext create(Request request, UriInfo uriInfo) {
        return new CassiniRequestContext(request, uriInfo);
    }
}
