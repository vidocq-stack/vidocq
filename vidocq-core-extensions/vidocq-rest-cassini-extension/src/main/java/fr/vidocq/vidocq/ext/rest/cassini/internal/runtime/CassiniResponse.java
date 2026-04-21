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

    private boolean closed = false;

    @Override public boolean hasEntity() { return entity != null; }
    @Override public boolean bufferEntity() {
        if (closed) throw new IllegalStateException("Response has been closed");
        // §4.3 : retourne false si aucun backing stream à buffer (entity
        // déjà stockée en mémoire). true uniquement si un stream existait
        // et a été copié.
        return false;
    }
    @Override public void close() { this.closed = true; }

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
        java.util.List<Object> vs = headers.get("Allow");
        if (vs == null || vs.isEmpty()) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Object v : vs) {
            if (v == null) continue;
            for (String tok : v.toString().split(",")) {
                String t = tok.trim();
                if (!t.isEmpty()) out.add(t.toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    @Override public Map<String, NewCookie> getCookies() {
        Map<String, NewCookie> out = new java.util.HashMap<>();
        java.util.List<Object> vs = headers.get("Set-Cookie");
        if (vs == null) return out;
        for (Object v : vs) {
            if (v instanceof NewCookie nc) out.put(nc.getName(), nc);
        }
        return out;
    }
    @Override public EntityTag getEntityTag() {
        Object v = headers.getFirst("ETag");
        if (v == null) return null;
        if (v instanceof EntityTag et) return et;
        try { return EntityTag.valueOf(v.toString()); } catch (Exception e) { return null; }
    }
    @Override public Date getDate() { return parseHttpDate(headers.getFirst("Date")); }
    @Override public Date getLastModified() { return parseHttpDate(headers.getFirst("Last-Modified")); }

    private static Date parseHttpDate(Object v) {
        if (v == null) return null;
        if (v instanceof Date d) return d;
        String s = v.toString();
        for (String fmt : new String[] {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"}) {
            try {
                return new java.text.SimpleDateFormat(fmt, Locale.US).parse(s);
            } catch (java.text.ParseException ignored) {}
        }
        return null;
    }

    @Override public URI getLocation() {
        Object v = headers.getFirst("Location");
        return v == null ? null : URI.create(v.toString());
    }

    @Override public Set<Link> getLinks() {
        java.util.List<Object> vs = headers.get("Link");
        if (vs == null || vs.isEmpty()) return Set.of();
        Set<Link> out = new java.util.LinkedHashSet<>();
        for (Object v : vs) {
            if (v instanceof Link l) { out.add(l); continue; }
            Link parsed = parseLink(v.toString());
            if (parsed != null) out.add(parsed);
        }
        return out;
    }
    @Override public boolean hasLink(String relation) {
        for (Link l : getLinks()) if (relation != null && relation.equals(l.getRel())) return true;
        return false;
    }
    @Override public Link getLink(String relation) {
        for (Link l : getLinks()) if (relation != null && relation.equals(l.getRel())) return l;
        return null;
    }
    @Override public Link.Builder getLinkBuilder(String relation) {
        Link l = getLink(relation);
        return l == null ? null : Link.fromLink(l);
    }

    private static Link parseLink(String raw) {
        try { return Link.valueOf(raw); } catch (Exception e) { return null; }
    }

    @Override public MultivaluedMap<String, Object> getMetadata() { return headers; }
    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }

    @Override public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) {
            for (Object v : e.getValue()) m.add(e.getKey(), headerToString(v));
        }
        return m;
    }

    @Override public String getHeaderString(String name) {
        java.util.List<Object> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(headerToString(v.get(i)));
        }
        return sb.toString();
    }

    /** Sérialise un header via HeaderDelegate si disponible (§4.3). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String headerToString(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        try {
            jakarta.ws.rs.ext.RuntimeDelegate.HeaderDelegate hd =
                    jakarta.ws.rs.ext.RuntimeDelegate.getInstance().createHeaderDelegate(v.getClass());
            if (hd != null) return hd.toString(v);
        } catch (Exception ignored) {}
        return String.valueOf(v);
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
