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
package io.vidocq.runtime.spi;

import java.util.Optional;

/**
 * State an extension hands to the next boot of the same JVM, during an in-JVM swap of {@code vidocq:dev}: the HTTP
 * sessions of a servlet deployment, for instance. Only {@code vidocq:dev} provides it. An extension must work
 * unchanged when {@link ExtensionContext#devHandover()} is empty, as it always is in a packaged application, a test
 * or an embedding.
 *
 * <p>Only bytes cross the swap: nothing of the old application layer is kept, so the old layer can be collected. A
 * key starts with a prefix the extension owns, such as {@code servlet:} in {@code servlet:sessions:/} (its
 * {@link VidocqExtension#name() name}, or a short form of it, keeps two extensions apart). Keys the next
 * boot did not take once it started are dropped.
 */
public interface DevHandover {

    /**
     * Whether the stop in progress hands state over: a swap whose request vouched for the HTTP sessions. Outside a
     * stop, and in a stop that drops them, {@code false}.
     *
     * @return {@code true} while {@link #put} accepts state
     */
    boolean exporting();

    /**
     * Keeps {@code value} for the next boot. A second put of a key replaces it.
     *
     * @param key   the key, starting with the extension's id
     * @param value the bytes, copied
     * @throws IllegalStateException when the store is not {@link #exporting()}
     */
    void put(String key, byte[] value);

    /**
     * The bytes the previous boot put under {@code key}, removed from the store.
     *
     * @param key the key the previous boot used
     * @return the bytes, or empty when the previous boot put none
     */
    Optional<byte[]> take(String key);
}
