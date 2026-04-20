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

    @Override public Response.ResponseBuilder createResponseBuilder() { return new CassiniResponseBuilder(); }

    @Override public Variant.VariantListBuilder createVariantListBuilder() {
        throw new UnsupportedOperationException("Variant list builder not implemented yet");
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
        throw new UnsupportedOperationException("Link.Builder not implemented yet");
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
}
