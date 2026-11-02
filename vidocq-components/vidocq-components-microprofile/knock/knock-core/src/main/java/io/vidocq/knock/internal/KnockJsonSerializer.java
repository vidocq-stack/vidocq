/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.knock.internal;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.spi.JsonProvider;
import org.eclipse.microprofile.health.HealthCheckResponse;

import java.util.Map;
import java.util.Optional;

/**
 * Serializes a {@link HealthSnapshot} into JSON compliant with MicroProfile Health 4.0 §3.1.
 *
 * <p>Expected format:</p>
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "checks": [
 *     {
 *       "name": "my-check",
 *       "status": "UP",
 *       "data": { "key": "value" }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Implementation via Jakarta JSON-P ({@link Json#createObjectBuilder()}) —
 * champollion is the allowed runtime implementation. No {@code StringBuilder}
 * and no third-party JSON library.</p>
 *
 * <p>Spec §3.1: {@code data} is omitted if absent ({@link Optional#empty()}) ;
 * do not serialize {@code "data":null}.</p>
 *
 * <p>The builders come from one {@link JsonBuilderFactory} resolved when the serializer is created:
 * {@link Json}'s static methods look the provider up through {@code ServiceLoader} and create it by
 * reflection at every call, that is at every {@code /health} request (BUG-20261102-01); the factory is shared
 * by all serializers, the {@code /health} resource creating one per request.</p>
 */
public final class KnockJsonSerializer {

    private final JsonBuilderFactory json;

    /** The factory of the JSON-P provider, resolved on first use and shared: a serializer is created per request. */
    private static final class DefaultFactory {
        static final JsonBuilderFactory INSTANCE = JsonProvider.provider().createBuilderFactory(Map.of());
    }

    /** Builds through the shared factory of the JSON-P provider. */
    public KnockJsonSerializer() {
        this(DefaultFactory.INSTANCE);
    }

    /** Builds through {@code json}. */
    KnockJsonSerializer(JsonBuilderFactory json) {
        this.json = json;
    }

    JsonBuilderFactory factory() {
        return json;
    }

    /**
     * Serializes the snapshot as a JSON string.
     *
     * @param snapshot the aggregated result to serialize
     * @return JSON string compliant with spec §3.1
     */
    public String serialize(HealthSnapshot snapshot) {
        JsonArrayBuilder checksArray = json.createArrayBuilder();
        for (HealthCheckResponse check : snapshot.checks()) {
            checksArray.add(buildCheckObject(check));
        }

        return json.createObjectBuilder()
                .add("status", snapshot.status().name())
                .add("checks", checksArray)
                .build()
                .toString();
    }

    /**
     * Returns the appropriate HTTP code.
     *
     * <p>Spec §3: HTTP 200 if {@code status=UP}, HTTP 503 if {@code status=DOWN}.</p>
     *
     * @param snapshot the aggregated result
     * @return 200 or 503
     */
    public int httpStatus(HealthSnapshot snapshot) {
        return switch (snapshot.status()) {
            case UP -> 200;
            case DOWN -> 503;
        };
    }

    private JsonObjectBuilder buildCheckObject(HealthCheckResponse check) {
        JsonObjectBuilder builder = json.createObjectBuilder()
                .add("name", check.getName())
                .add("status", check.getStatus().name());

        Optional<Map<String, Object>> data = check.getData();
        if (data.isPresent() && !data.get().isEmpty()) {
            builder.add("data", buildDataObject(data.get()));
        }

        return builder;
    }

    private JsonObjectBuilder buildDataObject(Map<String, Object> data) {
        JsonObjectBuilder dataBuilder = json.createObjectBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            switch (entry.getValue()) {
                case String s -> dataBuilder.add(entry.getKey(), s);
                case Long l   -> dataBuilder.add(entry.getKey(), l);
                case Boolean b -> dataBuilder.add(entry.getKey(), b);
                case Integer i -> dataBuilder.add(entry.getKey(), i);
                default        -> dataBuilder.add(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return dataBuilder;
    }
}
