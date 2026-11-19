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
