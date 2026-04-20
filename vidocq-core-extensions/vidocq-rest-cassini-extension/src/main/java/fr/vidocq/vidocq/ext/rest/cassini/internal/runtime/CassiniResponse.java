package fr.vidocq.vidocq.ext.rest.cassini.internal.runtime;

import fr.vidocq.vidocq.ext.rest.cassini.internal.MediaTypes;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Implémentation concrète de {@link Response} côté serveur (immutable).
 * Construite par {@link CassiniResponseBuilder}, consommée par l'Invoker
 * qui en extrait status / headers / entity pour sérialiser côté Chappe.
 */
public final class CassiniResponse extends Response {

    private final int status;
    private final String reason;
    private final Object entity;
    private final Annotation[] annotations;
    private final MultivaluedMap<String, Object> headers;

    public CassiniResponse(int status, String reason, Object entity, Annotation[] annotations,
                           MultivaluedMap<String, Object> headers) {
        this.status = status;
        this.reason = reason;
        this.entity = entity;
        this.annotations = annotations == null ? new Annotation[0] : annotations;
        this.headers = headers == null ? new MultivaluedHashMap<>() : headers;
    }

    public Annotation[] entityAnnotations() { return annotations; }

    @Override public int getStatus() { return status; }

    @Override public StatusType getStatusInfo() {
        StatusType s = Status.fromStatusCode(status);
        if (s != null) return s;
        final String r = reason == null ? "" : reason;
        return new StatusType() {
            @Override public int getStatusCode() { return status; }
            @Override public String getReasonPhrase() { return r; }
            @Override public Status.Family getFamily() { return Status.Family.familyOf(status); }
        };
    }

    @Override public Object getEntity() { return entity; }

    @Override public <T> T readEntity(Class<T> type) { return type.cast(entity); }
    @Override public <T> T readEntity(GenericType<T> type) { @SuppressWarnings("unchecked") T t = (T) entity; return t; }
    @Override public <T> T readEntity(Class<T> t, Annotation[] a) { return t.cast(entity); }
    @Override public <T> T readEntity(GenericType<T> t, Annotation[] a) { @SuppressWarnings("unchecked") T x = (T) entity; return x; }

    @Override public boolean hasEntity() { return entity != null; }
    @Override public boolean bufferEntity() { return false; }
    @Override public void close() { /* no-op */ }

    @Override public MediaType getMediaType() {
        Object v = headers.getFirst("Content-Type");
        if (v == null) return null;
        return v instanceof MediaType mt ? mt : MediaTypes.parse(v.toString());
    }

    @Override public Locale getLanguage() {
        Object v = headers.getFirst("Content-Language");
        return v == null ? null : Locale.forLanguageTag(v.toString());
    }

    @Override public int getLength() {
        Object v = headers.getFirst("Content-Length");
        if (v == null) return -1;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return -1; }
    }

    @Override public Set<String> getAllowedMethods() {
        Object v = headers.getFirst("Allow");
        if (v == null) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String tok : v.toString().split(",")) out.add(tok.trim().toUpperCase(Locale.ROOT));
        return out;
    }

    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public EntityTag getEntityTag() { return null; }
    @Override public Date getDate() { return null; }
    @Override public Date getLastModified() { return null; }

    @Override public URI getLocation() {
        Object v = headers.getFirst("Location");
        return v == null ? null : URI.create(v.toString());
    }

    @Override public Set<Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public Link getLink(String relation) { return null; }
    @Override public Link.Builder getLinkBuilder(String relation) { throw new UnsupportedOperationException(); }

    @Override public MultivaluedMap<String, Object> getMetadata() { return headers; }
    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }

    @Override public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) {
            for (Object v : e.getValue()) m.add(e.getKey(), String.valueOf(v));
        }
        return m;
    }

    @Override public String getHeaderString(String name) {
        java.util.List<Object> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(v.get(i));
        }
        return sb.toString();
    }

    public static MultivaluedMap<String, Object> immutable(MultivaluedMap<String, Object> h) {
        MultivaluedMap<String, Object> copy = new MultivaluedHashMap<>(h);
        return copy;
    }

    public static String formatHeader(Object v) {
        if (v == null) return "";
        if (v instanceof MediaType mt) return MediaTypes.format(mt);
        if (v instanceof Date d) return new java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).format(d);
        return v.toString();
    }

    public static MultivaluedMap<String, Object> newHeaders() { return new MultivaluedHashMap<>(); }

    static MultivaluedMap<String, Object> unmodifiable(MultivaluedMap<String, Object> h) {
        return h == null ? new MultivaluedHashMap<>() : h;
    }

    @Override public String toString() {
        return "CassiniResponse[status=" + status + ", entity=" + entity + "]";
    }

    static { Collections.emptyList(); } // silence imports
}
