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
package io.vidocq.runtime.devservices.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretMaskingTest {

    @ParameterizedTest
    @ValueSource(strings = {"vidocq.pool.password", "vidocq.pool.audit.password", "a.passwd", "a.pwd", "a.secret",
            "a.token", "mp.jwt.verify.publickey", "a.credentials", "a.credential", "a.apikey", "a.api-key",
            "a.private-key", "a.adminPassword", "A.PASSWORD"})
    void aKeyNamingASecretIsSecret(String key) {
        assertTrue(SecretMasking.isSecret(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"vidocq.pool.url", "vidocq.pool.username", "mp.jwt.verify.issuer", "a.keystore-type",
            "a.passwords-policy"})
    void otherKeysAreNot(String key) {
        assertFalse(SecretMasking.isSecret(key));
    }

    @Test
    void aUrlLosesItsCredentials() {
        assertEquals("jdbc:postgresql://***@localhost:5432/app",
                SecretMasking.withoutCredentials("jdbc:postgresql://app:s3cret@localhost:5432/app"));
        assertEquals("jdbc:postgresql://h/db?user=u&password=***&ssl=true",
                SecretMasking.withoutCredentials("jdbc:postgresql://h/db?user=u&password=p&ssl=true"));
        assertEquals("plain value", SecretMasking.withoutCredentials("plain value"));
        assertNull(SecretMasking.withoutCredentials(null));
    }

    /**
     * Query-parameter masking must use the very same rule as {@link SecretMasking#isSecret(String)} — any
     * parameter name that rule flags is masked, not only the fixed handful the old pattern hard-coded.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sslpassword", "client_secret", "access_token", "private-key", "sslkey", "PASSWORD"})
    void everySecretQueryParameterNameIsMaskedByTheKeyRule(String name) {
        String masked = SecretMasking.withoutCredentials("jdbc:postgresql://h/db?" + name + "=x");
        assertEquals("jdbc:postgresql://h/db?" + name + "=***", masked, masked);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "ssl", "sslmode"})
    void aNonSecretQueryParameterIsLeftUntouched(String name) {
        String value = "jdbc:postgresql://h/db?" + name + "=kept";
        assertEquals(value, SecretMasking.withoutCredentials(value));
    }

    @Test
    void aUrlMixingSecretAndNonSecretParamsInSeveralPositionsMasksOnlyTheSecretOnes() {
        String url = "jdbc:postgresql://h/db?sslpassword=x&user=u&client_secret=y&ssl=true&access_token=z"
                + "&sslmode=require;private-key=w";
        String expected = "jdbc:postgresql://h/db?sslpassword=***&user=u&client_secret=***&ssl=true&access_token=***"
                + "&sslmode=require;private-key=***";
        assertEquals(expected, SecretMasking.withoutCredentials(url));
    }

    /**
     * {@code Matcher.replaceAll(Function)} feeds the function's returned string straight into
     * {@code appendReplacement}, which treats an un-quoted {@code $} or {@code \} as a group reference or an
     * escape. A non-secret parameter's value is echoed verbatim on the pass-through branch and must never be
     * read that way — it is data, not a replacement template. Each input here must come back unchanged but for
     * {@code password=z} becoming {@code password=***}, and must never throw.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "user=abc$def&password=z",
            "user=abc\\&password=z",
            "user=$0xyz&password=z",
            "user=a\\1b&password=z"})
    void aNonSecretValueWithRegexMetacharactersIsNeverInterpretedAsAReplacementTemplate(String query) {
        String url = "jdbc:postgresql://h/db?" + query;
        String expected = url.replace("password=z", "password=***");
        assertEquals(expected, SecretMasking.withoutCredentials(url), url);
    }

    @Test
    void aSecretParameterNameContainingADollarStaysMaskedWithoutThrowing() {
        assertEquals("jdbc:postgresql://h/db?to$password=***",
                SecretMasking.withoutCredentials("jdbc:postgresql://h/db?to$password=z"));
    }
}
