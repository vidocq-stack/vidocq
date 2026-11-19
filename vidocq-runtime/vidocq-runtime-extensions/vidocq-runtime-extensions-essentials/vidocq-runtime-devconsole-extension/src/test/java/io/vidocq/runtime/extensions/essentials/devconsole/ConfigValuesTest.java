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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.report.LaunchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the {@code config} panel may show of a configured value: nothing outside a dev launch, {@code configured} for
 * a key that names a secret, and a URL without its credentials.
 */
class ConfigValuesTest {

    @ParameterizedTest
    @ValueSource(strings = {"db.password", "db.passwd", "db.pwd", "vidocq.mcp.secret", "github.token", "api.key",
            "aws.credentials", "aws.credential", "service.apikey", "service.api-key", "tls.private-key", "PASSWORD",
            "db.Password", "DB.PASSWORD", "db.adminPassword", "vidocq.mcp.requestStateSecret", "oauth.bearerToken",
            "stripe.secretKey", "DB_PASSWORD", "jwt.signing_key", "app.clientSecret"})
    void aKeyWhoseLastSegmentNamesASecretIsSecret(String key) {
        assertTrue(ConfigValues.isSecretKey(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"db.url", "db.password.file", "vidocq.http.port", "password.policy", "app.name",
            "token.lifetime", "db.passwordHash", "vidocq.secrets.dir", "tokens", "mp.config.profile", ""})
    void aKeyWhoseLastSegmentNamesNoSecretIsShown(String key) {
        assertFalse(ConfigValues.isSecretKey(key), key);
    }

    @Test
    void aNullKeyIsNoSecret() {
        assertFalse(ConfigValues.isSecretKey(null));
    }

    @Test
    void theValueOfASecretKeyIsConfigured() {
        assertEquals("configured", ConfigValues.shown("vidocq.mcp.requestStateSecret", "s3cr3t", LaunchMode.DEV));
        assertEquals("configured", ConfigValues.shown("db.adminPassword", "", LaunchMode.DEV),
                "even an empty secret is not shown");
    }

    @Test
    void aValueThatMerelyContainsPasswordIsShownWhenItsKeyIsNoSecret() {
        assertEquals("password", ConfigValues.shown("app.greeting", "password", LaunchMode.DEV));
        assertEquals("reset your password here", ConfigValues.shown("app.hint", "reset your password here",
                LaunchMode.DEV));
        assertEquals("/etc/app/password.txt", ConfigValues.shown("db.password.file", "/etc/app/password.txt",
                LaunchMode.DEV));
    }

    @Test
    void aPlainValueIsShownAsItIs() {
        assertEquals("8081", ConfigValues.shown("vidocq.http.port", "8081", LaunchMode.DEV));
        assertEquals("", ConfigValues.shown("app.empty", "", LaunchMode.DEV));
    }

    @Test
    void aNullValueIsNull() {
        assertNull(ConfigValues.shown("app.name", null, LaunchMode.DEV));
        assertNull(ConfigValues.withoutCredentials(null));
    }

    @ParameterizedTest
    @EnumSource(value = LaunchMode.class, names = "DEV", mode = EnumSource.Mode.EXCLUDE)
    void outsideADevLaunchNoValueIsShown(LaunchMode mode) {
        assertEquals("not shown outside dev", ConfigValues.shown("app.name", "orders", mode));
        assertEquals("not shown outside dev", ConfigValues.shown("db.password", "s3cr3t", mode));
    }

    @Test
    void theUserAndPasswordOfAUrlAreRemoved() {
        assertEquals("postgres://***@db.acme.com:5432/orders",
                ConfigValues.withoutCredentials("postgres://admin:s3cr3t@db.acme.com:5432/orders"));
        assertEquals("jdbc:postgresql://***@localhost/orders",
                ConfigValues.withoutCredentials("jdbc:postgresql://admin:s3cr3t@localhost/orders"));
        assertEquals("https://***@example.com",
                ConfigValues.withoutCredentials("https://token@example.com"));
        assertEquals("mongodb+srv://***@cluster0.example.net/?retryWrites=true",
                ConfigValues.withoutCredentials("mongodb+srv://u:p%40ss@cluster0.example.net/?retryWrites=true"));
    }

    @Test
    void aPasswordThatHoldsAnAtSignIsRemovedWhole() {
        String shown = ConfigValues.withoutCredentials("redis://user:p@ss@cache:6379/0");

        assertEquals("redis://***@cache:6379/0", shown);
    }

    @Test
    void everyUrlOfAListLosesItsCredentials() {
        assertEquals("http://***@a:1,http://***@b:2",
                ConfigValues.withoutCredentials("http://u:one@a:1,http://u:two@b:2"));
    }

    @Test
    void aSecretQueryParameterIsMasked() {
        assertEquals("jdbc:mysql://db/orders?user=app&password=***&ssl=true",
                ConfigValues.withoutCredentials("jdbc:mysql://db/orders?user=app&password=s3cr3t&ssl=true"));
        assertEquals("jdbc:sqlserver://db;databaseName=orders;pwd=***",
                ConfigValues.withoutCredentials("jdbc:sqlserver://db;databaseName=orders;pwd=s3cr3t"));
        assertEquals("https://api.acme.com/v1?PASSWORD=***",
                ConfigValues.withoutCredentials("https://api.acme.com/v1?PASSWORD=s3cr3t"));
        assertEquals("https://api.acme.com/v1?access_token=***#top",
                ConfigValues.withoutCredentials("https://api.acme.com/v1?access_token=abc#top"));
        assertEquals("https://api.acme.com/v1?apiKey=***&page=2",
                ConfigValues.withoutCredentials("https://api.acme.com/v1?apiKey=abc&page=2"));
    }

    @Test
    void aQueryParameterThatNamesNoSecretIsKept() {
        assertEquals("https://api.acme.com/v1?page=2&passwordPolicy=strict",
                ConfigValues.withoutCredentials("https://api.acme.com/v1?page=2&passwordPolicy=strict"));
    }

    @Test
    void aValueWithoutUrlIsKept() {
        assertEquals("admin@acme.com", ConfigValues.withoutCredentials("admin@acme.com"));
        assertEquals("a=b", ConfigValues.withoutCredentials("a=b"));
    }

    @Test
    void aShownUrlHasNoCredentials() {
        String shown = ConfigValues.shown("db.url", "jdbc:postgresql://admin:s3cr3t@db/x?password=other",
                LaunchMode.DEV);

        assertEquals("jdbc:postgresql://***@db/x?password=***", shown);
        assertFalse(shown.contains("s3cr3t") || shown.contains("other"), shown);
    }
}
