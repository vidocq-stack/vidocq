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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.host.StateFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;

/**
 * Writes {@code target/vidocq-dev-tests.json} (spec §2.4) by hand, atomically, the way {@link StateFile} writes the
 * dev services state: {@code state}, {@code trigger}, {@code startedAt} (to the second), {@code durationMillis},
 * {@code counts}, {@code failures}, {@code log}, and {@code previous} when there is one. The dev console's
 * {@code tests} panel reads it.
 */
final class TestResultsFile {

    /** The results file's name, under the build directory. */
    static final String FILE_NAME = "vidocq-dev-tests.json";
    /** The test runs' log, overwritten on every run. */
    static final String LOG_NAME = "vidocq-dev-tests.log";
    /** The system property that tells the application's dev console where the results file is. */
    static final String PROPERTY = "vidocq.dev.tests.results";

    private TestResultsFile() {}

    static String json(TestResults results) {
        StringBuilder b = new StringBuilder(256);
        object(b, results);
        return b.toString();
    }

    /** Writes {@code results} to {@code file} atomically: never half a document for the panel to read. */
    static void write(Path file, TestResults results) throws IOException {
        StateFile.write(file, json(results));
    }

    private static void object(StringBuilder b, TestResults r) {
        TestResults.Counts c = r.counts();
        b.append("{\"state\":").append(str(r.state().wire()))
                .append(",\"trigger\":").append(str(r.trigger().wire()))
                .append(",\"startedAt\":").append(str(r.startedAt().truncatedTo(ChronoUnit.SECONDS).toString()))
                .append(",\"durationMillis\":").append(r.durationMillis())
                .append(",\"counts\":{\"run\":").append(c.run())
                .append(",\"failures\":").append(c.failures())
                .append(",\"errors\":").append(c.errors())
                .append(",\"skipped\":").append(c.skipped())
                .append("},\"failures\":[");
        for (int i = 0; i < r.failures().size(); i++) {
            TestResults.Failure f = r.failures().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"test\":").append(str(f.test()))
                    .append(",\"type\":").append(str(f.type()))
                    .append(",\"message\":").append(str(f.message())).append('}');
        }
        b.append("],\"log\":").append(str(r.log()));
        if (r.previous() != null) {
            b.append(",\"previous\":");
            object(b, r.previous());
        }
        b.append('}');
    }

    private static String str(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
