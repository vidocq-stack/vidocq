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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.spi.DevHandover;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** The handover of the reload loop of {@code vidocq:dev}, over a map: the core's store, without the core. */
final class MapHandover implements DevHandover {

    final Map<String, byte[]> values = new HashMap<>();
    boolean exporting;
    int puts;

    @Override
    public boolean exporting() {
        return exporting;
    }

    @Override
    public void put(String key, byte[] value) {
        if (!exporting) {
            throw new IllegalStateException("put outside the stop of a swap");
        }
        puts++;
        values.put(key, value.clone());
    }

    @Override
    public Optional<byte[]> take(String key) {
        return Optional.ofNullable(values.remove(key));
    }
}
