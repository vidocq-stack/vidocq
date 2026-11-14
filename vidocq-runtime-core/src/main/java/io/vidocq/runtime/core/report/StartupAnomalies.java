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
package io.vidocq.runtime.core.report;

/**
 * The anomalies the core detects while it boots, and the logger they go to.
 *
 * <p>An anomaly is one WARNING record of {@value #LOGGER_NAME}, emitted where it is detected, its code
 * first: {@code [VIDOCQ-CFG-001] Invalid value ...}. One record and one line each, so that it survives a
 * boot that fails before any report, and so that it can be alerted on by its code. It is logged whatever
 * the level of the startup report, {@code off} included, and always through
 * {@link StartupRecorder#anomaly(String, String, String, String, Throwable)}, which cleans it first: the
 * {@code warn} methods here are that recorder's, and nothing else logs an anomaly.
 */
public final class StartupAnomalies {

    /** The logger of every anomaly the core emits. */
    public static final String LOGGER_NAME = "io.vidocq.startup.anomaly";

    /** {@code vidocq.startup.report} or {@code vidocq.launch.mode} has a value it does not accept. */
    public static final String INVALID_VALUE = "VIDOCQ-CFG-001";
    /** The audit of the configured keys failed; it never fails the boot. */
    public static final String AUDIT_FAILED = "VIDOCQ-CFG-002";
    /** A configured key under an audited namespace is read by nothing. */
    public static final String UNREAD_KEY = "VIDOCQ-CFG-003";
    /** Load-time weaving was needed and could not be prepared. */
    public static final String WEAVING_FAILED = "VAUBAN-009";
    /**
     * The application was built without {@code vidocq:generate}, as by an IDE, and the boot indexed at runtime the
     * bean archives no build step indexed (Vidocq/vidocq#241).
     */
    public static final String GENERATE_SKIPPED = "VIDOCQ-GEN-001";
    /**
     * The application module has bean classes and none of the files Vauban's annotation processor leaves: it was
     * compiled without the processor, as by an IDE whose annotation processing did not run, and its beans are lost.
     */
    public static final String UNINDEXED_BEANS = "VIDOCQ-GEN-002";
    /**
     * A production start ran the build compatible extensions because the record the build wrote of them is not this
     * deployment's: a class or a jar differs from what {@code vidocq:generate} froze (Vidocq/vauban#288). The message
     * names what differs.
     */
    public static final String RECORD_REFUSED = "VIDOCQ-GEN-003";
    /**
     * A startup report contributor could not be loaded, created or identified, or failed while it wrote its
     * section, which is skipped; logged with the stack trace when there is one.
     */
    public static final String CONTRIBUTOR_FAILED = "VIDOCQ-RPT-001";
    /** Two startup report contributors of different classes use the same id; the second is skipped. */
    public static final String DUPLICATE_ID = "VIDOCQ-RPT-002";
    /**
     * A {@code Vidocq.run} trampoline launched with the application on the module path, as an IDE runs a main class:
     * the application's modules are in the boot layer and in the application layer, and each of their classes exists
     * twice (BUG-20261114-10).
     */
    public static final String MODULES_TWICE = "VIDOCQ-LAY-001";

    private static final System.Logger LOG = System.getLogger(LOGGER_NAME);

    private StartupAnomalies() {}

    /** Logs {@code [code] message}, already cleaned by the recorder, at WARNING on {@value #LOGGER_NAME}. */
    static void warn(String code, String message) {
        LOG.log(System.Logger.Level.WARNING, withCode(code, message));
    }

    /**
     * Logs {@code [code] message}, already cleaned by the recorder, at WARNING on {@value #LOGGER_NAME}, with the
     * stack trace of {@code thrown}.
     */
    static void warn(String code, String message, Throwable thrown) {
        LOG.log(System.Logger.Level.WARNING, withCode(code, message), thrown);
    }

    /**
     * The {@value #MODULES_TWICE} message for the modules that the boot layer and the application layer both hold.
     *
     * @param modules their names
     */
    public static String modulesTwice(java.util.List<String> modules) {
        return modules.size() + (modules.size() == 1 ? " application module is" : " application modules are")
                + " in the boot layer and in the application layer: " + String.join(", ", modules) + ". The launch"
                + " put the application on the module path, as an IDE run of its main class does, and Vidocq.run()"
                + " loaded it again into the application layer. Each of their classes exists twice: a ServiceLoader"
                + " finds each of their providers twice, and a library that looks a class up by its name can get the"
                + " copy of the boot layer.";
    }

    /** The hint of {@value #MODULES_TWICE}: the launches that hand the application over without the module path. */
    public static final String MODULES_TWICE_HINT = "Run the application through vidocq:dev or vidocq:run, as the (dev)"
            + " and (run) configurations that vidocq:idea writes do: they hand it to the runtime without the module path.";

    /** {@code [code] message}, the shape of every anomaly. */
    public static String withCode(String code, String message) {
        return "[" + code + "] " + message;
    }

    /**
     * The {@value #INVALID_VALUE} message: {@code Invalid value 'detaild' for vidocq.startup.report
     * (auto, off, summary, detailed): using auto}.
     *
     * @param key      the configuration key
     * @param value    the value it has
     * @param accepted the values it accepts, as they are written
     */
    public static String invalidValue(String key, String value, String accepted) {
        return invalidValue(key, value, accepted, "auto");
    }

    /**
     * The {@value #INVALID_VALUE} message of a key whose default is not {@code auto}: {@code Invalid value 'yes' for
     * vidocq.startup.require-frozen-extensions (true, false): using false}.
     *
     * @param fallback the value used instead
     */
    public static String invalidValue(String key, String value, String accepted, String fallback) {
        return "Invalid value '" + value + "' for " + key + " (" + accepted + "): using " + fallback;
    }
}
