/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.Converter;

import java.io.Serial;

/**
 * Converter trivial pour {@code String} — retourne la valeur telle quelle.
 *
 * <p>Converter de base pour {@code String}. Les autres types sont couverts par
 * les converters built-in et implicites du noyau config.</p>
 */
final class IdentityStringConverter implements Converter<String> {

    @Serial
    private static final long serialVersionUID = 1L;

    static final IdentityStringConverter INSTANCE = new IdentityStringConverter();

    private IdentityStringConverter() {
        // singleton — utiliser INSTANCE
    }

    @Override
    public String convert(String value) {
        return value;
    }
}
