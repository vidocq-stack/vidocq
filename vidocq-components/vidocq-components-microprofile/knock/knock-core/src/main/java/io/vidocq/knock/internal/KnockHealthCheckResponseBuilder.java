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
 * Knock implementation of the MicroProfile Health 4.0 builder.
 *
 * <p>Spec §3: "A builder to construct a health check procedure response."
 * Produces a {@link HealthCheckResponse} via its public constructor.</p>
 *
 * <p>Instances created by {@link KnockHealthCheckResponseProvider} via ServiceLoader.</p>
 */
public final class KnockHealthCheckResponseBuilder extends HealthCheckResponseBuilder {

    private String name;
    private HealthCheckResponse.Status status;
    /** null until any withData call occurs — serialized as Optional.empty(). */
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
     * Builds the response.
     *
     * <p>Spec §3.1: {@code data} is {@link Optional#empty()} if no {@code withData}
     * call was made; otherwise it is wrapped in a non-empty {@link Optional}.</p>
     *
     * @return a new immutable {@link HealthCheckResponse}
     * @throws IllegalStateException if {@code name} or {@code status} is missing
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
