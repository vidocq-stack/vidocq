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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §3.5: a secret's name is masked wherever the inspector keeps or shows arguments. */
class SecretsTest {

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    @Test
    void aNameIsASecretsWhenItHoldsAMarkerIgnoringCase() {
        for (String name : List.of("password", "dbPasswd", "clientSecret", "accessToken", "apikey", "x-api-key",
                "api_key", "Credentials", "Authorization")) {
            assertTrue(Secrets.secret(name), name);
        }
        assertFalse(Secrets.secret("zone"));
        assertFalse(Secrets.secret("keyword"));
    }

    @Test
    void maskReplacesASecretMemberAtAnyDepth() {
        assertEquals(object("{\"zone\":\"UTC\",\"apiKey\":\"***\",\"auth\":{\"password\":\"***\"},"
                        + "\"list\":[{\"token\":\"***\"}]}"),
                Secrets.mask(object("{\"zone\":\"UTC\",\"apiKey\":\"k-123\",\"auth\":{\"password\":\"hunter22\"},"
                        + "\"list\":[{\"token\":{\"a\":1}}]}")));
    }

    @Test
    void theSecretValuesAreScrubbedFromAText() {
        Set<String> secrets = Secrets.values(object("{\"apiKey\":\"hunter22\",\"pin\":\"abc\",\"token\":\"abc\"}"));

        assertEquals(Set.of("hunter22", "abc"), secrets);
        assertEquals("Invalid key *** for abc", Secrets.scrub("Invalid key hunter22 for abc", secrets),
                "a value shorter than 4 characters would mask ordinary text");
    }

    @Test
    void scrubAlsoReplacesTheJsonEscapedFormOfASecret() {
        String secret = "sk-test\"quote";
        Set<String> secrets = Set.of(secret);

        assertEquals("key is *** here", Secrets.scrub("key is " + secret + " here", secrets),
                "the decoded form, as an error message would quote it");
        assertEquals("key is *** here", Secrets.scrub("key is sk-test\\\"quote here", secrets),
                "the JSON-escaped form, as raw JSON text embedding the secret would hold it");
    }

    @Test
    void scrubTreeReplacesEveryStringLeafWhereverItAppears() {
        String backslash = "a\\b\\c-1";
        String tab = "tab\there";
        String unicode = "clé-secrète";
        Set<String> secrets = Set.of(backslash, tab, unicode);
        JsonObject tree = Json.createObjectBuilder().add("apiKey", backslash).add("note", tab + tab + " again")
                .add("list", Json.createArrayBuilder().add(Json.createObjectBuilder()
                        .add("text", "unicode " + unicode + " here")))
                .build();

        JsonObject expected = Json.createObjectBuilder().add("apiKey", Secrets.MASK)
                .add("note", Secrets.MASK + Secrets.MASK + " again")
                .add("list", Json.createArrayBuilder().add(Json.createObjectBuilder()
                        .add("text", "unicode " + Secrets.MASK + " here")))
                .build();

        assertEquals(expected, Secrets.scrubTree(tree, secrets));
    }

    @Test
    void scrubTreeNeverTouchesAMemberNameEvenWhenItMatchesASecret() {
        Set<String> secrets = Set.of("keyname");
        JsonObject tree = Json.createObjectBuilder().add("keyname", "unrelated value").build();

        assertEquals(tree, Secrets.scrubTree(tree, secrets), "the name, not a value, must survive untouched");
    }

    @Test
    void valuesGathersEveryStringLeafOfAnArrayOrObjectUnderASecretName() {
        Set<String> secrets = Secrets.values(object("{\"apiKeys\":[\"sk-aaaa1111\",\"sk-bbbb2222\"],"
                + "\"auth\":{\"token\":{\"nested\":\"deep-secret-1\"}}}"));

        assertEquals(Set.of("sk-aaaa1111", "sk-bbbb2222", "deep-secret-1"), secrets);
    }

    @Test
    void valuesGathersANumberUnderASecretNameOfFourCharactersOrMoreButNoBoolean() {
        Set<String> secrets = Secrets.values(object("{\"otpToken\":123456,\"pinToken\":42,\"hasToken\":true,"
                + "\"auth\":{\"password\":[98765.5]},\"count\":123456}"));

        assertEquals(Set.of("123456", "98765.5"), secrets);
        assertEquals("code *** refused", Secrets.scrub("code 123456 refused", secrets));
    }
}
