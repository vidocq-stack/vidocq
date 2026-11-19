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
package io.vidocq.ravel.spi;

import org.eclipse.microprofile.config.spi.Converter;

/**
 * Implicit converters (MicroProfile Config 3.1 §5.2) generated at build time for the types an application injects
 * (Ravel's annotation processor): the {@code of}, {@code valueOf}, {@code parse} method or the {@code String}
 * constructor the specification picks, called directly instead of through reflection. Found through
 * {@link java.util.ServiceLoader}; a type none of them knows keeps the reflective discovery.
 */
public interface GeneratedConverters {

    /** The implicit converter generated for {@code type}, or {@code null} when none was. */
    <T> Converter<T> converter(Class<T> type);
}
