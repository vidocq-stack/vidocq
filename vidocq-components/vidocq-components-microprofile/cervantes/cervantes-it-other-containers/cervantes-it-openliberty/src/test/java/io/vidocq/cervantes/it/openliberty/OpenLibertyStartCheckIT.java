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
package io.vidocq.cervantes.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invalid {@code mp.jwt.*} configuration stops the deployment on Open Liberty (cervantes#52): the WAR of
 * {@link OpenLibertyPortabilityIT}, unchanged, on a second server whose JVM sets
 * {@code mp.jwt.verify.publickey.algorithm=rs256} (see the {@code pom.xml}), which Liberty's MicroProfile Config
 * reads. Cervantes' start check, a {@code Startup} observer, throws a {@code DeploymentException} while Liberty
 * initializes the web application, and Liberty does not start the application. The test reads the outcome in the
 * server's {@code messages.log}.
 */
class OpenLibertyStartCheckIT {

    private static final String APPLICATION = "cervantes-it-openliberty";
    /** Liberty's message: the application started. */
    private static final String STARTED = "CWWKZ0001I";
    /** Liberty's message: the application was not started. */
    private static final String NOT_STARTED = "CWWKZ0012I";
    /** Liberty's message: an exception was caught while the context of the web application initialized. */
    private static final String CONTEXT_INITIALIZATION_FAILED = "SRVE0283E";
    private static final String BASE = System.getProperty("cervantes.it.invalid.base");
    private static final Path LOG = Path.of(System.getProperty("cervantes.it.invalid.log"));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Liberty waits 30 s for an application before it reports it as slow; one minute leaves room on a busy runner. */
    private static final Duration DEPLOYMENT_OUTCOME_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void unrecognisedAlgorithmStopsTheDeployment() throws IOException, InterruptedException {
        String log = logOnceTheDeploymentEnded();

        assertTrue(log.lines().anyMatch(line -> line.contains(CONTEXT_INITIALIZATION_FAILED)
                && line.contains("jakarta.enterprise.inject.spi.DeploymentException: invalid MicroProfile JWT"
                        + " configuration (mp.jwt.verify.* / mp.jwt.decrypt.*): unsupported"
                        + " mp.jwt.verify.publickey.algorithm 'rs256'")), log);
        assertTrue(reports(log, NOT_STARTED), log);
        assertFalse(reports(log, STARTED), log);
        assertEquals(404, statusOf("/secured/public"));
    }

    /**
     * The server's log once it reports how the deployment of the application ended, started or not: the plugin's
     * start goal waits for the server, which does not wait for an application slower than its own timeout.
     */
    private static String logOnceTheDeploymentEnded() throws IOException {
        long deadline = System.nanoTime() + DEPLOYMENT_OUTCOME_TIMEOUT.toNanos();
        String log = read();
        while (!reports(log, STARTED) && !reports(log, NOT_STARTED) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
            log = read();
        }
        return log;
    }

    /** Whether a line of {@code log} has the message {@code code} about the application; the text is localized. */
    private static boolean reports(String log, String code) {
        return log.lines().anyMatch(line -> line.contains(code) && line.contains(APPLICATION));
    }

    private static String read() throws IOException {
        return Files.exists(LOG) ? Files.readString(LOG) : "";
    }

    private static int statusOf(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
