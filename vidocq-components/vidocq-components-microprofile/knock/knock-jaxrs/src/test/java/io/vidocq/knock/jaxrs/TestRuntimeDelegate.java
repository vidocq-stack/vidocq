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
 * Minimal {@link RuntimeDelegate} implementation for the JUnit tests of
 * {@link KnockHealthResource}.
 *
 * <p>Boundary respected: <strong>no internal Cassini class is imported</strong>. The
 * tests use this isolated delegate so they depend only on the standard JAX-RS API.
 * In production, the {@link RuntimeDelegate} implementation is provided by
 * Cassini through its own boot mechanism.</p>
 *
 * <p>Only {@link #createResponseBuilder()} is implemented (via {@link TestResponseBuilder});
 * the other methods throw {@link UnsupportedOperationException} — they are not required to
 * test {@code Response.status(...).type(...).entity(...).build()}.</p>
 */
final class TestRuntimeDelegate extends RuntimeDelegate {

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new TestResponseBuilder();
    }

    @Override
    public UriBuilder createUriBuilder() {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }

    @Override
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        // Minimal support for MediaType: MediaType.toString() goes through RuntimeDelegate.
        return new HeaderDelegate<>() {
            @SuppressWarnings("unchecked")
            @Override public T fromString(String value) {
                if (type == MediaType.class) {
                    int slash = value.indexOf('/');
                    return (T) new MediaType(value.substring(0, slash), value.substring(slash + 1));
                }
                throw new UnsupportedOperationException("fromString not needed for " + type);
            }
            @Override public String toString(T value) {
                if (value instanceof MediaType m) {
                    return m.getType() + "/" + m.getSubtype();
                }
                throw new UnsupportedOperationException("toString not needed for " + type);
            }
        };
    }

    @Override
    public Link.Builder createLinkBuilder() {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }

    @Override
    public EntityPart.Builder createEntityPartBuilder(String partName) {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }

    @Override
    public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application,
                                                           SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz,
                                                           SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException("not needed for KnockHealthResource tests");
    }
}

