/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.jaxrs;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal test {@link Response.ResponseBuilder} used by {@link TestRuntimeDelegate}.
 *
 * <p>Supports only the operations used by {@code KnockHealthResource}:
 * {@link #status(int)}, {@link #type(MediaType)}, {@link #entity(Object)},
 * {@link #build()}.</p>
 *
 * <p>The other {@code ResponseBuilder} methods are implemented as no-op /
 * {@code UnsupportedOperationException} — they are not used by the health resource.</p>
 */
final class TestResponseBuilder extends Response.ResponseBuilder {

    private int status = 200;
    private MediaType mediaType;
    private Object entity;

    @Override public Response build() {
        return new TestResponse(status, mediaType, entity);
    }
    @Override public Response.ResponseBuilder status(int status) {
        this.status = status;
        return this;
    }
    @Override public Response.ResponseBuilder status(int status, String reasonPhrase) {
        this.status = status;
        return this;
    }
    @Override public Response.ResponseBuilder entity(Object entity) {
        this.entity = entity;
        return this;
    }
    @Override public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) {
        this.entity = entity;
        return this;
    }
    @Override public Response.ResponseBuilder type(MediaType type) {
        this.mediaType = type;
        return this;
    }
    @Override public Response.ResponseBuilder type(String type) {
        this.mediaType = type == null ? null : MediaType.valueOf(type);
        return this;
    }

    // ---- Methods not used by KnockHealthResource ---------------------------------
    @Override public Response.ResponseBuilder allow(String... methods)            { return this; }
    @Override public Response.ResponseBuilder allow(Set<String> methods)          { return this; }
    @Override public Response.ResponseBuilder cacheControl(jakarta.ws.rs.core.CacheControl c) { return this; }
    @Override public Response.ResponseBuilder encoding(String encoding)           { return this; }
    @Override public Response.ResponseBuilder header(String name, Object value)   { return this; }
    @Override public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) { return this; }
    @Override public Response.ResponseBuilder language(String language)           { return this; }
    @Override public Response.ResponseBuilder language(Locale language)           { return this; }
    @Override public Response.ResponseBuilder variant(jakarta.ws.rs.core.Variant variant) { return this; }
    @Override public Response.ResponseBuilder contentLocation(URI location)       { return this; }
    @Override public Response.ResponseBuilder cookie(NewCookie... cookies)        { return this; }
    @Override public Response.ResponseBuilder expires(Date expires)               { return this; }
    @Override public Response.ResponseBuilder lastModified(Date lastModified)     { return this; }
    @Override public Response.ResponseBuilder location(URI location)              { return this; }
    @Override public Response.ResponseBuilder tag(EntityTag tag)                  { return this; }
    @Override public Response.ResponseBuilder tag(String tag)                     { return this; }
    @Override public Response.ResponseBuilder variants(jakarta.ws.rs.core.Variant... variants) { return this; }
    @Override public Response.ResponseBuilder variants(java.util.List<jakarta.ws.rs.core.Variant> v) { return this; }
    @Override public Response.ResponseBuilder links(Link... links)                { return this; }
    @Override public Response.ResponseBuilder link(URI uri, String rel)           { return this; }
    @Override public Response.ResponseBuilder link(String uri, String rel)        { return this; }
    @Override public Response.ResponseBuilder clone()                             { return this; }

    // ============================================================
    // TestResponse — implements only getStatus / getMediaType / getEntity
    // ============================================================
    private static final class TestResponse extends Response {
        private final int status;
        private final MediaType mediaType;
        private final Object entity;

        TestResponse(int status, MediaType mediaType, Object entity) {
            this.status = status;
            this.mediaType = mediaType;
            this.entity = entity;
        }

        @Override public int getStatus()                      { return status; }
        @Override public StatusType getStatusInfo()           {
            return Response.Status.fromStatusCode(status);
        }
        @Override public Object getEntity()                   { return entity; }
        @Override public MediaType getMediaType()             { return mediaType; }
        @Override public boolean hasEntity()                  { return entity != null; }
        @Override public boolean bufferEntity()               { return false; }
        @Override public void close()                         { /* no-op */ }
        @Override public MultivaluedMap<String, Object> getMetadata()
                                                              { return new MultivaluedHashMap<>(); }
        @Override public MultivaluedMap<String, String> getStringHeaders()
                                                              { return new MultivaluedHashMap<>(); }
        @Override public String getHeaderString(String name)  { return null; }
        @Override public Locale getLanguage()                 { return null; }
        @Override public int getLength()                      { return -1; }
        @Override public Set<String> getAllowedMethods()      { return new HashSet<>(); }
        @Override public java.util.Map<String, NewCookie> getCookies()
                                                              { return java.util.Map.of(); }
        @Override public EntityTag getEntityTag()             { return null; }
        @Override public Date getDate()                       { return null; }
        @Override public Date getLastModified()               { return null; }
        @Override public URI getLocation()                    { return null; }
        @Override public Set<Link> getLinks()                 { return new HashSet<>(); }
        @Override public boolean hasLink(String relation)     { return false; }
        @Override public Link getLink(String relation)        { return null; }
        @Override public Link.Builder getLinkBuilder(String relation) { return null; }
        @Override public <T> T readEntity(Class<T> entityType)        { throw uoe(); }
        @Override public <T> T readEntity(GenericType<T> entityType)  { throw uoe(); }
        @Override public <T> T readEntity(Class<T> entityType, Annotation[] annotations) { throw uoe(); }
        @Override public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) { throw uoe(); }

        private static UnsupportedOperationException uoe() {
            return new UnsupportedOperationException("readEntity not supported by TestResponse");
        }

        // GenericEntity / InputStream entity helpers — not used
        @SuppressWarnings("unused")
        private static GenericEntity<?> unusedGenericEntity() { return null; }
        @SuppressWarnings("unused")
        private static InputStream unusedInputStream()        { return null; }
    }
}

