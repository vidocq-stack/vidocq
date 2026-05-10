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

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import org.eclipse.microprofile.health.HealthCheckResponse;

import java.util.Map;
import java.util.Optional;

/**
 * Sérialise un {@link HealthSnapshot} en JSON conforme à la spec MicroProfile Health 4.0 §3.1.
 *
 * <p>Format attendu :</p>
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
 * <p>Implémentation via Jakarta JSON-P ({@link Json#createObjectBuilder()}) —
 * champollion est l'implémentation runtime autorisée. Aucun {@code StringBuilder}
 * ni librairie JSON tierce.</p>
 *
 * <p>Spec §3.1 : {@code data} est omis si absent ({@link Optional#empty()}) ;
 * ne pas sérialiser {@code "data":null}.</p>
 */
public final class KnockJsonSerializer {

    /**
     * Sérialise le snapshot en JSON string.
     *
     * @param snapshot le résultat agrégé à sérialiser
     * @return JSON string conforme spec §3.1
     */
    public String serialize(HealthSnapshot snapshot) {
        JsonArrayBuilder checksArray = Json.createArrayBuilder();
        for (HealthCheckResponse check : snapshot.checks()) {
            checksArray.add(buildCheckObject(check));
        }

        return Json.createObjectBuilder()
                .add("status", snapshot.status().name())
                .add("checks", checksArray)
                .build()
                .toString();
    }

    /**
     * Retourne le code HTTP approprié.
     *
     * <p>Spec §3 : HTTP 200 si {@code status=UP}, HTTP 503 si {@code status=DOWN}.</p>
     *
     * @param snapshot le résultat agrégé
     * @return 200 ou 503
     */
    public int httpStatus(HealthSnapshot snapshot) {
        return switch (snapshot.status()) {
            case UP -> 200;
            case DOWN -> 503;
        };
    }

    private JsonObjectBuilder buildCheckObject(HealthCheckResponse check) {
        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("name", check.getName())
                .add("status", check.getStatus().name());

        Optional<Map<String, Object>> data = check.getData();
        if (data.isPresent() && !data.get().isEmpty()) {
            builder.add("data", buildDataObject(data.get()));
        }

        return builder;
    }

    private JsonObjectBuilder buildDataObject(Map<String, Object> data) {
        JsonObjectBuilder dataBuilder = Json.createObjectBuilder();
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
