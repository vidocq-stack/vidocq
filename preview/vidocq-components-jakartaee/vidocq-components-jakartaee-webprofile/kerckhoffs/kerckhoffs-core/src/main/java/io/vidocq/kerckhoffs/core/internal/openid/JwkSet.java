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
package io.vidocq.kerckhoffs.core.internal.openid;

import java.io.StringReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/**
 * The signature keys of a provider, read from its JSON Web Key Set (RFC 7517). Only RSA keys meant for signatures are
 * kept; any other entry is ignored, so a token cannot be checked against a key of another kind.
 */
public final class JwkSet {

    private final Map<String, PublicKey> byId;

    private JwkSet(Map<String, PublicKey> byId) {
        this.byId = byId;
    }

    /** Reads the set; a document that is not a JSON Web Key Set is refused. */
    public static JwkSet parse(String json) {
        Map<String, PublicKey> keys = new HashMap<>();
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonObject document = reader.readObject();
            JsonArray entries = document.getJsonArray("keys");
            if (entries == null) {
                throw new IllegalArgumentException("The document has no \"keys\" array");
            }
            for (JsonValue entry : entries) {
                if (entry instanceof JsonObject key) {
                    add(keys, key);
                }
            }
        } catch (JsonException | ClassCastException e) {
            throw new IllegalArgumentException("The document is not a JSON Web Key Set", e);
        }
        return new JwkSet(Map.copyOf(keys));
    }

    private static void add(Map<String, PublicKey> keys, JsonObject key) {
        String id = key.getString("kid", null);
        boolean forSignature = "sig".equals(key.getString("use", "sig"));
        if (id == null || !forSignature || !"RSA".equals(key.getString("kty", null))) {
            return;
        }
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(key.getString("n")));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(key.getString("e")));
            keys.put(id, KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException | ClassCastException _) {
            // An unreadable key is left out: a token that names it finds no key and is refused.
        }
    }

    /** The key of that identifier, or {@code null}. */
    PublicKey find(String keyId) {
        return keyId == null ? null : byId.get(keyId);
    }
}
