/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper de profil MP Config 3.1 §7.5.
 *
 * <p>Pour un profil actif {@code dev}, la clé logique {@code app.url} lit
 * {@code %dev.app.url}. Le wrapper expose un ordinal {@code delegate + 1}
 * pour que les propriétés profilées masquent la clé non profilée de la même source.</p>
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

