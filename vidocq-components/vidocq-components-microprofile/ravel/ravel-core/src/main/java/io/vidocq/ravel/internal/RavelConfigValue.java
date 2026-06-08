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

import org.eclipse.microprofile.config.ConfigValue;

import java.util.Objects;

/**
 * Immutable {@link ConfigValue} implementation (MP Config 3.1 §2.1.5).
 *
 * <p>For a missing key, {@code Config.getConfigValue(name)} must return a
 * non-null {@code ConfigValue} where only {@code name} is set. Use
 * {@link #absent(String)} for that sentinel instance.</p>
 */
public record RavelConfigValue(
        String name,
        String value,
        String rawValue,
        String sourceName,
        int sourceOrdinal
) implements ConfigValue {

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public String getRawValue() {
        return rawValue;
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public int getSourceOrdinal() {
        return sourceOrdinal;
    }

    /**
     * Sentinel for a missing key: value/rawValue/sourceName=null, sourceOrdinal=0.
     *
     * @param name looked-up property name (never {@code null})
     */
    public static RavelConfigValue absent(String name) {
        Objects.requireNonNull(name, "name");
        return new RavelConfigValue(name, null, null, null, 0);
    }
}
