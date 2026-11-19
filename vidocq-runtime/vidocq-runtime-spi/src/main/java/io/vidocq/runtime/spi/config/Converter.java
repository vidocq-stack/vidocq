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
package io.vidocq.runtime.spi.config;

/**
 * Raw string to target type converter.
 * <p>
 * Implementations are saved in the implementation layer of
 * {@link VidocqConfig}. The API is aligned with {@code org.eclipse.microprofile.config.spi.Converter}.
 * </p>
 *
 * <p><b>Raw string to target type converter.</b>
 * API aligned with MicroProfile Config {@code Converter}.</p>
 *
 * @param <T> target type / target type
 */
@FunctionalInterface
public interface Converter<T> {

    /**
     * Converts the raw value. May throw {@link IllegalArgumentException} if invalid.
     * Returning {@code null} means "empty value".
     * <p>Convert the raw value. May throw {@link IllegalArgumentException} if invalid.
     * Returning {@code null} means "empty value".</p>
     */
    T convert(String value);
}
