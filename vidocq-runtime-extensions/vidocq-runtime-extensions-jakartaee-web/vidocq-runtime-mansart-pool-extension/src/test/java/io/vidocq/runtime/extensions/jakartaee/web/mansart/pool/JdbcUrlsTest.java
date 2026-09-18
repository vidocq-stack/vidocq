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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JdbcUrls}: a JDBC URL as the startup report and the dev console may show it, its credentials removed, and
 * never more than the sub-protocol when it cannot be read.
 */
class JdbcUrlsTest {

    /** Every secret the cases below hide somewhere in a URL: none may survive the redaction. */
    private static final List<String> SECRETS =
            List.of("s3cret", "t1ger", "k3y", "hunter2", "tok-9", "c0nf",
                    "pa;ss", "pa?ss", "ti;g", "ti?g", "Xy7", "Qp4");

    /** The URL, what the report shows of it, and whether credentials were removed. */
    static Stream<Arguments> urls() {
        return Stream.of(
                // nothing to remove
                Arguments.of("jdbc:h2:mem:mansart-vidocq-demo;DB_CLOSE_DELAY=-1",
                        "jdbc:h2:mem:mansart-vidocq-demo;DB_CLOSE_DELAY=-1", false),
                Arguments.of("jdbc:postgresql://localhost:5432/app", "jdbc:postgresql://localhost:5432/app", false),
                Arguments.of("jdbc:postgresql://localhost:54213/vidocq?loggerLevel=OFF",
                        "jdbc:postgresql://localhost:54213/vidocq?loggerLevel=OFF", false),
                Arguments.of("jdbc:oracle:thin:@db.example:1521:orcl", "jdbc:oracle:thin:@db.example:1521:orcl", false),
                Arguments.of("jdbc:oracle:thin:@//db.example:1521/svc", "jdbc:oracle:thin:@//db.example:1521/svc",
                        false),
                // an @ in the value of a user parameter or setting is no user info: an e-mail, Azure's user@server
                Arguments.of("jdbc:postgresql://h/db?user=app@example.com", "jdbc:postgresql://h/db?user=app@example.com",
                        false),
                Arguments.of("jdbc:sqlserver://h:1433;database=app;user=admin@server;encrypt=true",
                        "jdbc:sqlserver://h:1433;database=app;user=admin@server;encrypt=true", false),
                Arguments.of("jdbc:h2:tcp://h/~/db;USER=app@example.com", "jdbc:h2:tcp://h/~/db;USER=app@example.com",
                        false),
                // an e-mail address is the other @ a setting may hold
                Arguments.of("jdbc:bigquery://h:443;ProjectId=p;OAuthServiceAcctEmail=sa@p.example",
                        "jdbc:bigquery://h:443;ProjectId=p;OAuthServiceAcctEmail=sa@p.example", false),
                // user info after //
                Arguments.of("jdbc:mysql://app:s3cret@db.example:3306/app", "jdbc:mysql://db.example:3306/app", true),
                Arguments.of("jdbc:mysql://app:s3@cret@db.example/app", "jdbc:mysql://db.example/app", true),
                // a password may hold a /, as a base64 one does: user info runs through the last @ before ? or ;
                Arguments.of("jdbc:mysql://app:Xy7/Qp4@db.example/app", "jdbc:mysql://db.example/app", true),
                Arguments.of("jdbc:mysql://app:s3@Xy7/Qp4@db.example/app", "jdbc:mysql://db.example/app", true),
                // so an @ in a path is taken for the end of user info: too much is hidden, never too little
                Arguments.of("jdbc:postgresql://host/db@x", "jdbc:postgresql://x", true),
                Arguments.of("jdbc:postgresql://app@db.example/app", "jdbc:postgresql://db.example/app", true),
                Arguments.of("jdbc:mariadb://app:s3cret@db.example/app?useSsl=true",
                        "jdbc:mariadb://db.example/app?useSsl=true", true),
                // Oracle: user/password@ with no //
                Arguments.of("jdbc:oracle:thin:scott/t1ger@db.example:1521:orcl",
                        "jdbc:oracle:thin:@db.example:1521:orcl", true),
                Arguments.of("jdbc:oracle:thin:scott/t1ger@//db.example:1521/svc",
                        "jdbc:oracle:thin:@//db.example:1521/svc", true),
                Arguments.of("jdbc:oracle:thin:scott/t1:ger@db.example:1521:orcl",
                        "jdbc:oracle:thin:@db.example:1521:orcl", true),
                // ?password= and its kin
                Arguments.of("jdbc:postgresql://h/db?user=app&password=s3cret&ssl=true",
                        "jdbc:postgresql://h/db?user=app&ssl=true", true),
                Arguments.of("jdbc:postgresql://h/db?password=s3cret&ssl=true", "jdbc:postgresql://h/db?ssl=true", true),
                Arguments.of("jdbc:mariadb://h/db?password=s3cret", "jdbc:mariadb://h/db", true),
                Arguments.of("jdbc:postgresql://h/db?sslpassword=k3y&sslmode=verify-full&PWD=hunter2&apiToken=tok-9"
                        + "&clientSecret=s3cret&aws.credentialsFile=c0nf",
                        "jdbc:postgresql://h/db?sslmode=verify-full", true),
                // ;PASSWORD= and the ; settings
                Arguments.of("jdbc:h2:tcp://h/~/db;USER=sa;PASSWORD=s3cret;DB_CLOSE_DELAY=-1",
                        "jdbc:h2:tcp://h/~/db;USER=sa;DB_CLOSE_DELAY=-1", true),
                Arguments.of("jdbc:sqlserver://h:1433;databaseName=app;password={s3;cret};encrypt=true",
                        "jdbc:sqlserver://h:1433;databaseName=app;encrypt=true", true),
                Arguments.of("jdbc:sqlserver://h;user=admin@server;password=P@s3cret;encrypt=false",
                        "jdbc:sqlserver://h;user=admin@server;encrypt=false", true),
                Arguments.of("jdbc:db2://h:50000/app:user=app;password=s3cret;",
                        "jdbc:db2://h:50000/app:user=app;", true),
                // both at once
                Arguments.of("jdbc:mysql://app:s3cret@h/db?password=hunter2", "jdbc:mysql://h/db", true),
                // what cannot be read shows its sub-protocol only: fail closed
                Arguments.of("jdbc:mysql://h/db?password={s3cret", "jdbc:mysql:…", false),
                Arguments.of("jdbc:mysql://address=(host=h)(password=s3cret)/db", "jdbc:mysql:…", false),
                Arguments.of("jdbc:db2://h:50000/app:password=s3cret;user=app;", "jdbc:db2:…", false),
                Arguments.of("jdbc:weird@s3cret:x", "jdbc:…", false),
                // a password holding a ? or a ; ends the part user info is looked for in before its @: an @ past it,
                // anywhere but in the value of a user parameter or setting, may close user info, so fail closed
                Arguments.of("jdbc:mysql://app:pa;ss@db.example/app", "jdbc:mysql:…", false),
                Arguments.of("jdbc:mysql://app:pa?ss@db.example/app", "jdbc:mysql:…", false),
                Arguments.of("jdbc:postgresql://app:pa;ss@db.example/app", "jdbc:postgresql:…", false),
                Arguments.of("jdbc:mysql://app:Xy7;9=Qp4@db.example/app", "jdbc:mysql:…", false),
                Arguments.of("jdbc:mysql://app:s3@Xy7;9=Qp4@db.example/app", "jdbc:mysql:…", false),
                Arguments.of("jdbc:oracle:thin:scott/ti;ger@db.example:1521:orcl", "jdbc:oracle:…", false),
                Arguments.of("jdbc:oracle:thin:scott/ti?ger@db.example:1521:orcl", "jdbc:oracle:…", false),
                Arguments.of("jdbc:oracle:thin:scott/ti;g=Qp4@db.example:1521:orcl", "jdbc:oracle:…", false),
                Arguments.of("jdbc:postgresql://h/db?ApplicationName=Xy7@host", "jdbc:postgresql:…", false),
                Arguments.of("jdbc:", "jdbc:…", false),
                Arguments.of("s3cret", "jdbc:…", false),
                Arguments.of(null, "jdbc:…", false));
    }

    @ParameterizedTest
    @MethodSource("urls")
    void theCredentialsAreRemoved(String url, String shown, boolean removed) {
        JdbcUrls.Redacted redacted = JdbcUrls.redacted(url);

        assertEquals(shown, redacted.url(), url);
        assertEquals(shown, JdbcUrls.redact(url), url);
        assertEquals(removed, redacted.credentialsRemoved(), url);
        for (String secret : SECRETS) {
            assertFalse(redacted.url().contains(secret), secret + " left in " + redacted.url());
        }
    }

    @Test
    void theKindIsTheSubProtocolAndHowH2IsReached() {
        assertEquals("postgresql", JdbcUrls.kind("jdbc:postgresql://app:s3cret@h/db"));
        assertEquals("oracle", JdbcUrls.kind("jdbc:oracle:thin:@h:1521:orcl"));
        assertEquals("sqlserver", JdbcUrls.kind("jdbc:sqlserver://h:1433;databaseName=app"));
        assertEquals("h2 mem", JdbcUrls.kind("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1"));
        assertEquals("h2 file", JdbcUrls.kind("jdbc:h2:file:/var/data/demo"));
        assertEquals("h2 file", JdbcUrls.kind("jdbc:h2:~/demo"));
        assertEquals("h2 tcp", JdbcUrls.kind("jdbc:h2:tcp://localhost/~/demo"));
        assertEquals("h2 ssl", JdbcUrls.kind("jdbc:H2:SSL://localhost/~/demo"));
        assertEquals("unknown", JdbcUrls.kind("s3cret"));
        assertEquals("unknown", JdbcUrls.kind("jdbc:we ird:x"));
        assertEquals("unknown", JdbcUrls.kind(null));
    }

    @Test
    void theAuthorityIsTheHostAndPortOfAServerUrl() {
        assertEquals("localhost:54213", JdbcUrls.authority("jdbc:postgresql://localhost:54213/vidocq?loggerLevel=OFF"));
        assertEquals("h:1433", JdbcUrls.authority("jdbc:sqlserver://h:1433;databaseName=app"));
        assertEquals("db.example", JdbcUrls.authority("jdbc:mysql://app:s3cret@db.example/app"));
        assertNull(JdbcUrls.authority("jdbc:h2:mem:demo"));
        assertNull(JdbcUrls.authority("jdbc:oracle:thin:@db.example:1521:orcl"));
        assertNull(JdbcUrls.authority(null));
        // what the redactor cannot read has no authority either: never the user info it could not remove
        assertNull(JdbcUrls.authority("jdbc:mysql://app:pa;ss@db.example/app"));
        assertNull(JdbcUrls.authority("jdbc:postgresql://app:pa?ss@db.example/app"));
        assertEquals("db.example", JdbcUrls.authority("jdbc:mysql://app:Xy7/Qp4@db.example/app"));
    }
}
