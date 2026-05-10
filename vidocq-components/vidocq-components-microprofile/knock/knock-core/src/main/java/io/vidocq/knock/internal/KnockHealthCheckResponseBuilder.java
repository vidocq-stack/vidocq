/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.internal;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implémentation Knock du builder MicroProfile Health 4.0.
 *
 * <p>Spec §3 : « A builder to construct a health check procedure response. »
 * Produit un {@link HealthCheckResponse} via son constructeur public.</p>
 *
 * <p>Instances créées par {@link KnockHealthCheckResponseProvider} via ServiceLoader.</p>
 */
public final class KnockHealthCheckResponseBuilder extends HealthCheckResponseBuilder {

    private String name;
    private HealthCheckResponse.Status status;
    /** null tant qu'aucun withData n'est appelé — sérialisé comme Optional.empty(). */
    private Map<String, Object> data;

    @Override
    public HealthCheckResponseBuilder name(String name) {
        this.name = name;
        return this;
    }

    @Override
    public HealthCheckResponseBuilder withData(String key, String value) {
        ensureData().put(key, value);
        return this;
    }

    @Override
    public HealthCheckResponseBuilder withData(String key, long value) {
        ensureData().put(key, value);
        return this;
    }

    @Override
    public HealthCheckResponseBuilder withData(String key, boolean value) {
        ensureData().put(key, value);
        return this;
    }

    @Override
    public HealthCheckResponseBuilder up() {
        this.status = HealthCheckResponse.Status.UP;
        return this;
    }

    @Override
    public HealthCheckResponseBuilder down() {
        this.status = HealthCheckResponse.Status.DOWN;
        return this;
    }

    @Override
    public HealthCheckResponseBuilder status(boolean up) {
        this.status = up ? HealthCheckResponse.Status.UP : HealthCheckResponse.Status.DOWN;
        return this;
    }

    /**
     * Construit la réponse.
     *
     * <p>Spec §3.1 : {@code data} est {@link Optional#empty()} si aucun {@code withData}
     * n'a été appelé ; sinon wrappé dans un {@link Optional} non vide.</p>
     *
     * @return une nouvelle {@link HealthCheckResponse} immuable
     * @throws IllegalStateException si {@code name} ou {@code status} est absent
     */
    @Override
    public HealthCheckResponse build() {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("HealthCheckResponse name must not be null or blank");
        }
        if (status == null) {
            throw new IllegalStateException("HealthCheckResponse status must be set (up() or down())");
        }
        return new HealthCheckResponse(name, status,
                data == null ? Optional.empty() : Optional.of(Map.copyOf(data)));
    }

    private Map<String, Object> ensureData() {
        if (data == null) {
            data = new LinkedHashMap<>();
        }
        return data;
    }
}
