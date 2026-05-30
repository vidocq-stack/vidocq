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
