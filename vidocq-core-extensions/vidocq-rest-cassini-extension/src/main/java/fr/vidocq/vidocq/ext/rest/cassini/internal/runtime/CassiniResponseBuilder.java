package fr.vidocq.vidocq.ext.rest.cassini.internal.runtime;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Implémentation de {@link jakarta.ws.rs.core.Response.ResponseBuilder}.
 * Couvre les setters fréquents (status / entity / header / type / location
 * / cookie / allow / cacheControl). Les variants et links complets
 * arriveront avec un M2e-2 si le TCK les réclame.
 */
public final class CassiniResponseBuilder extends Response.ResponseBuilder {

    private int status = 200;
    private String reason;
    private Object entity;
    private Annotation[] annotations;
    private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    @Override public Response build() {
        return new CassiniResponse(status, reason, entity, annotations, copy(headers));
    }

    @Override public Response.ResponseBuilder clone() {
        CassiniResponseBuilder c = new CassiniResponseBuilder();
        c.status = status;
        c.reason = reason;
        c.entity = entity;
        c.annotations = annotations;
        c.headers.putAll(headers);
        return c;
    }

    @Override public Response.ResponseBuilder status(int s) { this.status = s; this.reason = null; return this; }
    @Override public Response.ResponseBuilder status(int s, String r) { this.status = s; this.reason = r; return this; }

    @Override public Response.ResponseBuilder entity(Object e) { this.entity = e; return this; }
    @Override public Response.ResponseBuilder entity(Object e, Annotation[] a) { this.entity = e; this.annotations = a; return this; }

    @Override public Response.ResponseBuilder allow(String... methods) {
        if (methods == null) headers.remove("Allow");
        else headers.putSingle("Allow", String.join(", ", methods));
        return this;
    }

    @Override public Response.ResponseBuilder allow(Set<String> methods) {
        if (methods == null) headers.remove("Allow");
        else headers.putSingle("Allow", String.join(", ", methods));
        return this;
    }

    @Override public Response.ResponseBuilder cacheControl(CacheControl cc) {
        if (cc == null) headers.remove("Cache-Control");
        else headers.putSingle("Cache-Control", cc);
        return this;
    }

    @Override public Response.ResponseBuilder encoding(String encoding) {
        if (encoding == null) headers.remove("Content-Encoding");
        else headers.putSingle("Content-Encoding", encoding);
        return this;
    }

    @Override public Response.ResponseBuilder header(String name, Object value) {
        if (value == null) headers.remove(name);
        else headers.add(name, value);
        return this;
    }

    @Override public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> h) {
        headers.clear();
        if (h != null) headers.putAll(h);
        return this;
    }

    @Override public Response.ResponseBuilder language(String lang) {
        if (lang == null) headers.remove("Content-Language");
        else headers.putSingle("Content-Language", lang);
        return this;
    }

    @Override public Response.ResponseBuilder language(Locale lang) {
        if (lang == null) headers.remove("Content-Language");
        else headers.putSingle("Content-Language", lang.toLanguageTag());
        return this;
    }

    @Override public Response.ResponseBuilder type(MediaType type) {
        if (type == null) headers.remove("Content-Type");
        else headers.putSingle("Content-Type", type);
        return this;
    }

    @Override public Response.ResponseBuilder type(String type) {
        if (type == null) headers.remove("Content-Type");
        else headers.putSingle("Content-Type", type);
        return this;
    }

    @Override public Response.ResponseBuilder variant(Variant variant) {
        if (variant == null) return this;
        if (variant.getMediaType() != null) type(variant.getMediaType());
        if (variant.getLanguage() != null) language(variant.getLanguage());
        if (variant.getEncoding() != null) encoding(variant.getEncoding());
        return this;
    }

    @Override public Response.ResponseBuilder contentLocation(URI location) {
        if (location == null) headers.remove("Content-Location");
        else headers.putSingle("Content-Location", location.toString());
        return this;
    }

    @Override public Response.ResponseBuilder cookie(NewCookie... cookies) {
        if (cookies == null) { headers.remove("Set-Cookie"); return this; }
        for (NewCookie c : cookies) headers.add("Set-Cookie", c);
        return this;
    }

    @Override public Response.ResponseBuilder expires(Date expires) {
        if (expires == null) headers.remove("Expires");
        else headers.putSingle("Expires", expires);
        return this;
    }

    @Override public Response.ResponseBuilder lastModified(Date lm) {
        if (lm == null) headers.remove("Last-Modified");
        else headers.putSingle("Last-Modified", lm);
        return this;
    }

    @Override public Response.ResponseBuilder location(URI location) {
        if (location == null) headers.remove("Location");
        else headers.putSingle("Location", location.toString());
        return this;
    }

    @Override public Response.ResponseBuilder tag(EntityTag tag) {
        if (tag == null) headers.remove("ETag");
        else headers.putSingle("ETag", tag);
        return this;
    }

    @Override public Response.ResponseBuilder tag(String tag) {
        if (tag == null) headers.remove("ETag");
        else headers.putSingle("ETag", tag);
        return this;
    }

    @Override public Response.ResponseBuilder variants(Variant... variants) { return this; }
    @Override public Response.ResponseBuilder variants(List<Variant> variants) { return this; }
    @Override public Response.ResponseBuilder links(Link... links) { return this; }
    @Override public Response.ResponseBuilder link(URI uri, String rel) { return this; }
    @Override public Response.ResponseBuilder link(String uri, String rel) { return this; }

    private static MultivaluedMap<String, Object> copy(MultivaluedMap<String, Object> src) {
        return new MultivaluedHashMap<>(src);
    }
}
