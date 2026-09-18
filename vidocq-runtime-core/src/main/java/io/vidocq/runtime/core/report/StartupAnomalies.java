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
 * the level of the startup report, {@code off} included.
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

    private static final System.Logger LOG = System.getLogger(LOGGER_NAME);

    private StartupAnomalies() {}

    /** Logs {@code [code] message} at WARNING on {@value #LOGGER_NAME}. */
    public static void warn(String code, String message) {
        LOG.log(System.Logger.Level.WARNING, withCode(code, message));
    }

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
        return "Invalid value '" + value + "' for " + key + " (" + accepted + "): using auto";
    }
}
