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
