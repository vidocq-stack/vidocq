/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.Converter;

import java.io.Serial;

/**
 * Converter trivial pour {@code String} — retourne la valeur telle quelle.
 *
 * <p>Seul converter supporté en M1 (cf. ROADMAP.md) ; M2 ajoutera les built-in
 * pour les primitives et les types {@code java.time}/{@code java.net}, ainsi
 * que les implicit converters via {@code valueOf}/{@code parse}/{@code (String)}.</p>
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
