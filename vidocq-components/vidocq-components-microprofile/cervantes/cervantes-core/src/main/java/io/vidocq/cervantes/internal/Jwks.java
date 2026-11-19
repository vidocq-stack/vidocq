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
package io.vidocq.cervantes.internal;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;

/**
 * Unchangeable view of a resolved JWK Set: keys indexed by {@code kid} and complete list (for the case
 * a token without {@code kid} and a single key set).
 *
 * @param byKid public keys indexed by their {@code kid} (keys without {@code kid} absent)
 * @param all valid public keys of the set (order of appearance)
 */
record Jwks(Map<String, PublicKey> byKid, List<PublicKey> all) {

    Jwks {
        byKid = Map.copyOf(byKid);
        all = List.copyOf(all);
    }

    static final Jwks EMPTY = new Jwks(Map.of(), List.of());
}
