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

import org.eclipse.microprofile.config.Config;

/**
 * Creates and fills one {@code @ConfigProperties} class with code generated at build time, in the class's own
 * package (Ravel's annotation processor), instead of reflection. Found through {@link java.util.ServiceLoader};
 * a class without a generated factory keeps the reflective path.
 */
public interface GeneratedConfigProperties {

    /** The binary name of the {@code @ConfigProperties} class this factory creates. */
    String typeName();

    /**
     * The prefix the class declares with {@code @ConfigProperties(prefix = …)}, or
     * {@link org.eclipse.microprofile.config.inject.ConfigProperties#UNCONFIGURED_PREFIX} when it declares none.
     */
    String classPrefix();

    /**
     * A new instance of the class, each field set from {@code config} under {@code prefix} (empty for none) the way
     * MicroProfile Config 3.1 §6.4 maps them; a field with no value keeps its initial value.
     */
    Object create(String prefix, Config config);
}
