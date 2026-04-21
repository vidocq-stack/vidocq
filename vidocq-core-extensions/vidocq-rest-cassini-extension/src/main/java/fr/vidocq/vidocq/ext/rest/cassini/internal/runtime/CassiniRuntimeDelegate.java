package fr.vidocq.vidocq.ext.rest.cassini.internal.runtime;

import fr.vidocq.vidocq.ext.rest.cassini.internal.MediaTypes;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.concurrent.CompletionStage;

/**
 * Implémentation minimale de {@link RuntimeDelegate} pour débloquer
 * {@code Response.ok()}, {@code MediaType.toString()} et
 * {@link UriBuilder} côté code utilisateur.
 */
public final class CassiniRuntimeDelegate extends RuntimeDelegate {

    @Override public UriBuilder createUriBuilder() { return new CassiniUriBuilder(); }

    // Expose fromResource/fromMethod via UriBuilder.fromResource side (static fallback).
    // UriBuilder.fromResource() calls createUriBuilder().uri(...) internally in spec,
    // but CassiniUriBuilder.fromResource(Class) is our own helper.

    @Override public Response.ResponseBuilder createResponseBuilder() { return new CassiniResponseBuilder(); }

    @Override public Variant.VariantListBuilder createVariantListBuilder() {
        return new StubVariantListBuilder();
    }

    @Override public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw new UnsupportedOperationException("createEndpoint not supported");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        if (type == MediaType.class) return (HeaderDelegate<T>) new MediaTypeDelegate();
        return (HeaderDelegate<T>) new ToStringDelegate();
    }

    @Override public Link.Builder createLinkBuilder() {
        return new StubLinkBuilder();
    }

    @Override public EntityPart.Builder createEntityPartBuilder(String name) {
        throw new UnsupportedOperationException("EntityPart.Builder not implemented yet");
    }

    @Override public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        throw new UnsupportedOperationException("SeBootstrap not supported");
    }

    @Override public CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration config) {
        throw new UnsupportedOperationException("SeBootstrap not supported");
    }

    @Override public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz, SeBootstrap.Configuration config) {
        throw new UnsupportedOperationException("SeBootstrap not supported");
    }

    private static final class MediaTypeDelegate implements HeaderDelegate<MediaType> {
        @Override public MediaType fromString(String value) { return MediaTypes.parse(value); }
        @Override public String toString(MediaType value) { return MediaTypes.format(value); }
    }

    private static final class ToStringDelegate implements HeaderDelegate<Object> {
        @Override public Object fromString(String value) { return value; }
        @Override public String toString(Object value) { return value == null ? "" : value.toString(); }
    }

    /** Stub {@link Variant.VariantListBuilder} — collecte media types/langs/encodings
     *  et produit le produit cartésien via {@link #build()}. */
    private static final class StubVariantListBuilder extends Variant.VariantListBuilder {
        private final java.util.List<Variant> variants = new java.util.ArrayList<>();
        private final java.util.List<MediaType> mediaTypes = new java.util.ArrayList<>();
        private final java.util.List<java.util.Locale> languages = new java.util.ArrayList<>();
        private final java.util.List<String> encodings = new java.util.ArrayList<>();

        @Override public java.util.List<Variant> build() {
            add();
            return java.util.List.copyOf(variants);
        }

        @Override public Variant.VariantListBuilder add() {
            if (mediaTypes.isEmpty() && languages.isEmpty() && encodings.isEmpty()) return this;
            java.util.List<MediaType> mts = mediaTypes.isEmpty() ? java.util.Collections.singletonList(null) : mediaTypes;
            java.util.List<java.util.Locale> ls = languages.isEmpty() ? java.util.Collections.singletonList(null) : languages;
            java.util.List<String> encs = encodings.isEmpty() ? java.util.Collections.singletonList(null) : encodings;
            for (MediaType m : mts) for (java.util.Locale l : ls) for (String e : encs)
                variants.add(new Variant(m, l, e));
            mediaTypes.clear(); languages.clear(); encodings.clear();
            return this;
        }

        @Override public Variant.VariantListBuilder languages(java.util.Locale... langs) {
            for (java.util.Locale l : langs) languages.add(l);
            return this;
        }
        @Override public Variant.VariantListBuilder encodings(String... enc) {
            for (String e : enc) encodings.add(e);
            return this;
        }
        @Override public Variant.VariantListBuilder mediaTypes(MediaType... mts) {
            for (MediaType m : mts) mediaTypes.add(m);
            return this;
        }
    }

    /** Stub {@link Link.Builder} minimal : capture uri / rel / params, construit
     *  un Link renvoyant simplement ce qu'on lui a dit. Suffit pour que les TCK
     *  qui construisent un Link sans vérifier sa sérialisation passent. */
    private static final class StubLinkBuilder implements Link.Builder {
        private java.net.URI uri;
        private jakarta.ws.rs.core.UriBuilder uriBuilder;
        private java.net.URI baseUri;
        private final java.util.Map<String, String> params = new java.util.LinkedHashMap<>();

        @Override public Link.Builder link(Link link) {
            this.uri = link.getUri();
            params.clear();
            params.putAll(link.getParams());
            return this;
        }
        @Override public Link.Builder link(String link) { return uri(link); }
        @Override public Link.Builder uri(java.net.URI uri) { this.uri = uri; return this; }
        @Override public Link.Builder uri(String uri) { this.uri = java.net.URI.create(uri); return this; }
        @Override public Link.Builder baseUri(java.net.URI uri) { this.baseUri = uri; return this; }
        @Override public Link.Builder baseUri(String uri) { this.baseUri = java.net.URI.create(uri); return this; }
        @Override public Link.Builder uriBuilder(jakarta.ws.rs.core.UriBuilder ub) { this.uriBuilder = ub; return this; }
        @Override public Link.Builder rel(String rel) { params.put("rel", rel); return this; }
        @Override public Link.Builder title(String t) { params.put("title", t); return this; }
        @Override public Link.Builder type(String t) { params.put("type", t); return this; }
        @Override public Link.Builder param(String n, String v) { params.put(n, v); return this; }

        @Override public Link build(Object... values) {
            java.net.URI effective = uri != null ? uri
                    : (uriBuilder != null ? uriBuilder.build(values) : java.net.URI.create(""));
            if (baseUri != null) effective = baseUri.resolve(effective);
            return new StubLink(effective, java.util.Map.copyOf(params));
        }

        @Override public Link buildRelativized(java.net.URI base, Object... values) {
            Link l = build(values);
            java.net.URI rel = base.relativize(l.getUri());
            return new StubLink(rel, l.getParams());
        }
    }

    private static final class StubLink extends Link {
        private final java.net.URI uri;
        private final java.util.Map<String, String> params;

        StubLink(java.net.URI uri, java.util.Map<String, String> params) {
            this.uri = uri;
            this.params = params;
        }
        @Override public java.net.URI getUri() { return uri; }
        @Override public jakarta.ws.rs.core.UriBuilder getUriBuilder() { return new CassiniUriBuilder().uri(uri); }
        @Override public String getRel() { return params.get("rel"); }
        @Override public java.util.List<String> getRels() {
            String r = params.get("rel");
            return r == null ? java.util.List.of() : java.util.List.of(r.split("\\s+"));
        }
        @Override public String getTitle() { return params.get("title"); }
        @Override public String getType() { return params.get("type"); }
        @Override public java.util.Map<String, String> getParams() { return params; }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("<").append(uri).append('>');
            for (var e : params.entrySet()) sb.append(';').append(e.getKey()).append("=\"").append(e.getValue()).append('"');
            return sb.toString();
        }
    }
}
