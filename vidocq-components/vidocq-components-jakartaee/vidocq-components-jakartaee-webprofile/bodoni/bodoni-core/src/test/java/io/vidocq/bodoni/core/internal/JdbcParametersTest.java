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

package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** The JDBC parameter string of a data source, Tags 3.0 §10.1.1 and §10.2. */
class JdbcParametersTest {

    private static final String SECRET = "s3cret";

    @Test
    void specExampleGivesUrlAndDriver() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:mysql://localhost/,org.gjt.mm.mysql.Driver");
        assertEquals("jdbc:mysql://localhost/", parameters.url());
        assertEquals("org.gjt.mm.mysql.Driver", parameters.driver());
        assertEquals("", parameters.user());
        assertEquals("", parameters.password());
    }

    @Test
    void urlAloneHasNoDriverAndEmptyCredentials() {
        JdbcParameters parameters = JdbcParameters.parse("u");
        assertEquals("u", parameters.url());
        assertNull(parameters.driver());
        assertEquals("", parameters.user());
        assertEquals("", parameters.password());
    }

    @Test
    void emptyDriverIsAbsent() {
        JdbcParameters parameters = JdbcParameters.parse("u,,sa,");
        assertNull(parameters.driver());
        assertEquals("sa", parameters.user());
        assertEquals("", parameters.password());
    }

    @Test
    void passwordTakesTheRestOfTheString() {
        JdbcParameters parameters = JdbcParameters.parse("u,d,sa,p,w");
        assertEquals("u", parameters.url());
        assertEquals("d", parameters.driver());
        assertEquals("sa", parameters.user());
        assertEquals("p,w", parameters.password());
    }

    @Test
    void escapedCommaStaysInTheField() {
        assertEquals("jdbc:x:a,b", JdbcParameters.parse("jdbc:x:a\\,b,d").url());
    }

    @Test
    void escapedBackslashEndsBeforeTheSeparator() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:x:a\\\\,d");
        assertEquals("jdbc:x:a\\", parameters.url());
        assertEquals("d", parameters.driver());
    }

    @Test
    void backslashBeforeAnotherCharacterIsKept() {
        assertEquals("a\\qb", JdbcParameters.parse("a\\qb").url());
    }

    @Test
    void nullIsRefused() {
        assertThrows(NullPointerException.class, () -> JdbcParameters.parse(null));
    }

    @Test
    void redactedUrlDropsPropertiesAfterSemicolon() {
        assertEquals("jdbc:h2:mem:x", JdbcParameters.parse("jdbc:h2:mem:x;PASSWORD=" + SECRET).redactedUrl());
    }

    @Test
    void redactedUrlDropsUserInfoAndQuery() {
        assertEquals("jdbc:x://h:5432/db",
                JdbcParameters.parse("jdbc:x://u:" + SECRET + "@h:5432/db?password=" + SECRET).redactedUrl());
    }

    @Test
    void redactedUrlDropsUserInfoWithASlashInThePassword() {
        assertEquals("jdbc:x://h/db", JdbcParameters.parse("jdbc:x://u:pa/ss@h/db").redactedUrl());
    }

    @Test
    void redactedUrlDropsOracleCredentials() {
        assertEquals("jdbc:oracle:thin:@host:1521:db",
                JdbcParameters.parse("jdbc:oracle:thin:scott/tiger@host:1521:db").redactedUrl());
    }

    @Test
    void redactedUrlKeepsAnOracleUrlWithoutCredentials() {
        assertEquals("jdbc:oracle:thin:@host:1521:db",
                JdbcParameters.parse("jdbc:oracle:thin:@host:1521:db").redactedUrl());
    }

    @Test
    void oraclePasswordWithAColon() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:oracle:thin:scott/ti:ger@host:1521:db");
        assertEquals("jdbc:oracle:thin:@host:1521:db", parameters.redactedUrl());
        assertEquals(Set.of("ti:ger"), parameters.secrets());
    }

    @Test
    void oraclePasswordWithAnAt() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:oracle:thin:scott/p@ss@host:1521:db");
        assertEquals("jdbc:oracle:thin:@host:1521:db", parameters.redactedUrl());
        assertEquals(Set.of("p@ss"), parameters.secrets());
    }

    @Test
    void oracleCredentialsBeforeAnEzConnectAuthority() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:oracle:thin:scott/tiger@//host:1521/svc");
        assertEquals("jdbc:oracle:thin:@//host:1521/svc", parameters.redactedUrl());
        assertEquals(Set.of("tiger"), parameters.secrets());
    }

    @Test
    void userInfoPasswordWithASemicolon() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:mysql://u:pa;ss@host/db");
        assertEquals("jdbc:mysql://host/db", parameters.redactedUrl());
        assertEquals(Set.of("pa;ss"), parameters.secrets());
    }

    @Test
    void userInfoPasswordWithAQuestionMark() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:mysql://u:pa?ss@host/db");
        assertEquals("jdbc:mysql://host/db", parameters.redactedUrl());
        assertEquals(Set.of("pa?ss"), parameters.secrets());
    }

    @Test
    void oraclePasswordWithASemicolon() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:oracle:thin:scott/ti;ger@host:1521:db");
        assertEquals("jdbc:oracle:thin:@host:1521:db", parameters.redactedUrl());
        assertEquals(Set.of("ti;ger"), parameters.secrets());
    }

    @Test
    void propertiesAfterAUserInfoPasswordWithASemicolonAreCut() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:x://u:pa;ss@h/db;password=prop");
        assertEquals("jdbc:x://h/db", parameters.redactedUrl());
        assertEquals(Set.of("pa;ss", "prop"), parameters.secrets());
    }

    /** An {@code @} in a password property is no user information: no fragment of it shows. */
    @Test
    void passwordPropertyWithAnAt() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:x://h/db?password=p@ss");
        String redacted = parameters.redactedUrl();
        assertFalse(redacted.contains("ss"), redacted);
        assertTrue(parameters.secrets().contains("p@ss"), parameters.secrets().toString());
    }

    /** User information followed by a password property with an {@code @}: neither shows. */
    @Test
    void userInfoThenPasswordPropertyWithAnAt() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:x://u:" + SECRET + "@h/db;PWD=q@r");
        String redacted = parameters.redactedUrl();
        assertFalse(redacted.contains(SECRET), redacted);
        assertFalse(redacted.contains("r"), redacted);
        assertTrue(parameters.secrets().containsAll(Set.of(SECRET, "q@r")), parameters.secrets().toString());
    }

    /** An {@code @} in another property may cut more than the credentials, never less. */
    @Test
    void userInfoThenAnotherPropertyWithAnAt() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:x://u:" + SECRET + "@h/db;USER=a@b");
        String redacted = parameters.redactedUrl();
        assertFalse(redacted.contains(SECRET), redacted);
        assertTrue(parameters.secrets().contains(SECRET), parameters.secrets().toString());
    }

    /** An {@code @} in any property value is no user information: no part of the value shows. */
    @Test
    void anAtInAnyPropertyValueShowsNoneOfIt() {
        assertNoneShown("jdbc:x://h/db;passwd=p@ss", "ss");
        assertNoneShown("jdbc:x://h/db;pass=se@cret;x=1", "cret");
        assertNoneShown("jdbc:x://h/db;token=abc@def", "def");
    }

    private static void assertNoneShown(String url, String fragment) {
        String redacted = JdbcParameters.parse(url).redactedUrl();
        assertEquals("jdbc:x://", redacted);
        assertFalse(redacted.contains(fragment), redacted);
    }

    /** A {@code //} and an {@code @} that only occur in the properties are no user information. */
    @Test
    void anAuthorityInThePropertiesIsNoUserInfo() {
        JdbcParameters parameters = JdbcParameters.parse("jdbc:x:h;password=" + SECRET + ";url=//a@b");
        assertEquals("jdbc:x:h", parameters.redactedUrl());
        assertTrue(parameters.secrets().contains(SECRET), parameters.secrets().toString());
    }

    @Test
    void everyPasswordLikePropertyIsASecret() {
        JdbcParameters parameters = JdbcParameters.parse(
                "jdbc:x://h/db;sslpassword=one;trustStorePassword=two?PWD={three};user=sa&Pwd=four");
        assertEquals(Set.of("one", "two", "{three}", "three", "four"), parameters.secrets());
    }

    @Test
    void secretsAreThePasswordFieldAndThePasswordsOfTheUrl() {
        assertEquals(Set.of("field", "info", "prop", "query"),
                JdbcParameters.parse("jdbc:x://u:info@h/db;PASSWORD=prop?password=query,d,sa,field").secrets());
        assertEquals(Set.of("tiger"), JdbcParameters.parse("jdbc:oracle:thin:scott/tiger@host:1521:db").secrets());
        assertEquals(Set.of(), JdbcParameters.parse("jdbc:h2:mem:x;USER=sa,,sa,").secrets());
    }

    @Test
    void redactedUrlKeepsAJndiName() {
        assertEquals("jdbc/myDatabase", JdbcParameters.parse("jdbc/myDatabase").redactedUrl());
    }

    @Test
    void redactIsTheRedactedUrlOfTheParsedString() {
        assertEquals("jdbc:h2:mem:x", JdbcParameters.redact("jdbc:h2:mem:x;USER=sa,org.h2.Driver,sa," + SECRET));
    }

    @Test
    void toStringShowsNeitherUserNorPassword() {
        String text = JdbcParameters.parse("u,d,sa," + SECRET).toString();
        assertEquals("JdbcParameters[url=u, driver=d]", text);
        assertFalse(text.contains(SECRET), text);
        assertFalse(text.contains("sa"), text);
    }
}
