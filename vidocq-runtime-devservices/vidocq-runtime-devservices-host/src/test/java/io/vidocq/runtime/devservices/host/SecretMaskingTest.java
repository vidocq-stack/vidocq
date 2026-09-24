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
}
