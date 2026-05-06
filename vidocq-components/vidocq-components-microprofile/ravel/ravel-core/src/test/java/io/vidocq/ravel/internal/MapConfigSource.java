/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fixture de test — double manuel d'un {@link ConfigSource} adossé à une {@link Map}.
 *
 * <p>Utilisé par les tests de {@link RavelConfig} et {@link RavelConfigBuilder} pour
 * fabriquer des sources contrôlées sans dépendre de l'environnement. Pas de test propre.</p>
 */
record MapConfigSource(String name, int ordinal, Map<String, String> data) implements ConfigSource {

    MapConfigSource {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(data, "data");
    }

    static MapConfigSource of(String name, int ordinal, Map<String, String> entries) {
        return new MapConfigSource(name, ordinal, Map.copyOf(entries));
    }

    @Override
    public Set<String> getPropertyNames() {
        return data.keySet();
    }

    @Override
    public Map<String, String> getProperties() {
        return data;
    }

    @Override
    public String getValue(String propertyName) {
        return data.get(propertyName);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrdinal() {
        return ordinal;
    }
}
