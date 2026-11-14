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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.report.StartupAnomalies;
import io.vidocq.runtime.core.report.StartupRecorder;
import io.vidocq.vauban.core.container.ExtensionsAtStart;

import java.util.function.BiConsumer;

/**
 * What a boot says, and does, when its build compatible extensions ran at start instead of loading the record
 * {@code vidocq:generate} wrote of them (Vidocq/vauban#288).
 *
 * <p>Vauban refuses a record written for another deployment, and its note names the classes that differ. Whether
 * that is a mistake depends on the launch: a dev launch adds the dev console and its panels to the path, a test or an
 * Arquillian deployment adds its own classes, and both expect the extensions to run at start; there the note is one
 * INFO line. A production start should load what its build froze, and a refusal there, a jar added to the path or a
 * class changed since the build, is the anomaly {@value StartupAnomalies#RECORD_REFUSED}. A start with no record at
 * all says nothing more than before: {@code VIDOCQ-GEN-001} already covers an application built without
 * {@code vidocq:generate}.
 *
 * <p>{@value #REQUIRE_KEY}{@code =true} makes any of those starts fail, a refused record or none: the integration
 * tests that start a packaged application set it, so that a deployment the build did not freeze cannot pass unseen.
 */
final class RefusedRecord {

    /** {@code true}: a start whose build compatible extensions ran because no record of the build was used fails. */
    static final String REQUIRE_KEY = "vidocq.startup.require-frozen-extensions";

    private static final System.Logger LOG = System.getLogger(RefusedRecord.class.getName());

    private RefusedRecord() {}

    /**
     * Says why the extensions ran at start, at the level the launch calls for, and fails the start when
     * {@code required} and no record was used.
     *
     * @param atStart  what the container start did with the build compatible extensions
     * @param expected whether this launch expects a refused record: a dev or test launch, an embedded deployment
     * @param required the value of {@value #REQUIRE_KEY}
     * @param recorder the recorder of this boot, which logs the anomaly
     * @throws IllegalStateException when {@code required} and the extensions ran at start without a record
     */
    static void report(ExtensionsAtStart atStart, boolean expected, boolean required, StartupRecorder recorder) {
        if (atStart.frozen() || atStart.ranAtStart().isEmpty()) {
            return;
        }
        String ran = ran(atStart.ranAtStart().size());
        if (required) {
            throw new IllegalStateException(StartupAnomalies.withCode(StartupAnomalies.RECORD_REFUSED,
                    REQUIRE_KEY + " is true and " + ran + ": " + (atStart.note() != null ? atStart.note()
                            : "no record of the build is on the path; build the application with vidocq:generate")
                            + "."));
        }
        if (atStart.note() == null) {
            return;
        }
        String message = ran + ": " + atStart.note() + ".";
        if (expected) {
            LOG.log(System.Logger.Level.INFO, message);
        } else {
            recorder.anomaly(StartupAnomalies.RECORD_REFUSED, message, "Start the application on the path its build"
                    + " packaged, or build it again: a jar added to the path, or a class changed since the build,"
                    + " makes another deployment, and its extensions run at each start.", StartupRecorder.CORE);
        }
    }

    /**
     * The value of {@value #REQUIRE_KEY}: {@code true} or {@code false}, in any case; anything else is reported as
     * {@code VIDOCQ-CFG-001} and read as {@code false}.
     *
     * @param value     the configured value, stripped, or {@code null} when unset
     * @param anomalies where an invalid value is reported, as (code, message)
     */
    static boolean required(String value, BiConsumer<String, String> anomalies) {
        if (value == null || value.equalsIgnoreCase("false")) {
            return false;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        anomalies.accept(StartupAnomalies.INVALID_VALUE,
                StartupAnomalies.invalidValue(REQUIRE_KEY, value, "true, false", "false"));
        return false;
    }

    /** {@code 1 build compatible extension ran at start}, {@code 2 build compatible extensions ran at start}. */
    private static String ran(int count) {
        return count + (count == 1 ? " build compatible extension ran" : " build compatible extensions ran")
                + " at start";
    }
}
