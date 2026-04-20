package fr.vidocq.vidocq.ext.rest.cassini.internal.filter;

import fr.vidocq.vidocq.ext.rest.cassini.internal.MediaTypes;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Implémentation {@link ContainerResponseContext} autour d'une
 * représentation interne mutable de la réponse sortante (status + headers
 * + entity). Les filtres response peuvent changer tout cela avant le
 * MBW.
 */
public final class CassiniResponseContext implements ContainerResponseContext {

    private int status;
    private Response.StatusType statusInfo;
    private Object entity;
    private Type entityType;
    private Annotation[] annotations = new Annotation[0];
    private final MultivaluedMap<String, Object> headers;
    private OutputStream entityStream;

    public CassiniResponseContext(int status, Object entity, Type entityType,
                                  MultivaluedMap<String, Object> headers) {
        this.status = status;
        this.statusInfo = Response.Status.fromStatusCode(status);
        this.entity = entity;
        this.entityType = entityType;
        this.headers = headers == null ? new MultivaluedHashMap<>() : headers;
    }

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int code) { this.status = code; this.statusInfo = Response.Status.fromStatusCode(code); }
    @Override public Response.StatusType getStatusInfo() { return statusInfo; }
    @Override public void setStatusInfo(Response.StatusType si) { this.statusInfo = si; this.status = si.getStatusCode(); }

    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }

    @Override public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) for (Object v : e.getValue()) m.add(e.getKey(), String.valueOf(v));
        return m;
    }

    @Override public String getHeaderString(String name) {
        java.util.List<Object> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) { if (i > 0) sb.append(','); sb.append(v.get(i)); }
        return sb.toString();
    }

    @Override public boolean containsHeaderString(String n, String sep, java.util.function.Predicate<String> p) {
        java.util.List<Object> vs = headers.get(n);
        if (vs == null) return false;
        for (Object v : vs) for (String tok : v.toString().split(sep)) if (p.test(tok.trim())) return true;
        return false;
    }

    @Override public boolean containsHeaderString(String n, java.util.function.Predicate<String> p) {
        return containsHeaderString(n, ",", p);
    }

    @Override public Set<String> getAllowedMethods() { return Set.of(); }
    @Override public Date getDate() { return null; }
    @Override public Locale getLanguage() { return null; }
    @Override public int getLength() { return -1; }
    @Override public MediaType getMediaType() {
        Object v = headers.getFirst("Content-Type");
        if (v == null) return null;
        return v instanceof MediaType mt ? mt : MediaTypes.parse(v.toString());
    }
    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public EntityTag getEntityTag() { return null; }
    @Override public Date getLastModified() { return null; }
    @Override public URI getLocation() {
        Object v = headers.getFirst("Location");
        return v == null ? null : URI.create(v.toString());
    }
    @Override public Set<Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public Link getLink(String relation) { return null; }
    @Override public Link.Builder getLinkBuilder(String relation) { throw new UnsupportedOperationException(); }

    @Override public boolean hasEntity() { return entity != null; }
    @Override public Object getEntity() { return entity; }
    @Override public Class<?> getEntityClass() { return entity == null ? null : entity.getClass(); }
    @Override public Type getEntityType() { return entityType; }
    @Override public void setEntity(Object entity) { this.entity = entity; if (entity != null) this.entityType = entity.getClass(); }
    @Override public void setEntity(Object entity, Annotation[] anns, MediaType mt) {
        this.entity = entity;
        this.annotations = anns == null ? new Annotation[0] : anns;
        if (entity != null) this.entityType = entity.getClass();
        if (mt != null) this.headers.putSingle("Content-Type", mt);
    }
    @Override public Annotation[] getEntityAnnotations() { return annotations; }
    @Override public OutputStream getEntityStream() { return entityStream; }
    @Override public void setEntityStream(OutputStream out) { this.entityStream = out; }
}
