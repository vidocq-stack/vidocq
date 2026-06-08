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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * MP Config 3.1 §7.5 profile wrapper.
 *
 * <p>For active profile {@code dev}, logical key {@code app.url} resolves to
 * {@code %dev.app.url}. The wrapper exposes ordinal {@code delegate + 1} so
 * profiled keys shadow non-profiled keys from the same source.</p>
 */
final class ProfiledConfigSource implements ConfigSource {

    private final ConfigSource delegate;
    private final String profile;
    private final String prefix;

    ProfiledConfigSource(ConfigSource delegate, String profile) {
        this.delegate = delegate;
        this.profile = profile;
        this.prefix = "%" + profile + ".";
    }

    @Override
    public Map<String, String> getProperties() {
        var mapped = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : delegate.getProperties().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(prefix)) {
                mapped.put(k.substring(prefix.length()), e.getValue());
            }
        }
        return mapped;
    }

    @Override
    public Set<String> getPropertyNames() {
        return getProperties().keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return delegate.getValue(prefix + propertyName);
    }

    @Override
    public String getName() {
        return "ProfiledConfigSource[" + profile + "](" + delegate.getName() + ")";
    }

    @Override
    public int getOrdinal() {
        return delegate.getOrdinal() + 1;
    }
}

