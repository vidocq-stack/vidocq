package io.vidocq.cervantes.internal;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;

/**
 * Vue immuable d'un JWK Set résolu : clés indexées par {@code kid} et liste complète (pour le cas
 * d'un token sans {@code kid} et d'un set à clé unique).
 *
 * @param byKid clés publiques indexées par leur {@code kid} (clés sans {@code kid} absentes)
 * @param all   toutes les clés publiques valides du set (ordre d'apparition)
 */
record Jwks(Map<String, PublicKey> byKid, List<PublicKey> all) {

    Jwks {
        byKid = Map.copyOf(byKid);
        all = List.copyOf(all);
    }

    static final Jwks EMPTY = new Jwks(Map.of(), List.of());
}
