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

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.ArrayList;
import java.util.List;

/**
 * What one boot records for its startup report, from {@code VidocqBootstrap.configure()} to the report: the
 * duration of each phase ({@link System#nanoTime()}), the phase under way when something fails, the
 * anomalies, and the facts that are only known while the boot runs. One per bootstrap: the dev reload loop
 * boots several times in one JVM, each boot with its own recorder.
 *
 * <p>It holds strings and numbers only, never a class, so that nothing of an application layer outlives
 * the boot that loaded it. Everything happens on the booting thread; the anomalies alone are guarded, in
 * case another thread reports one.
 *
 * <p>The report is one INFO record of {@value #LOGGER_NAME}; a failed boot is one WARNING record of the same
 * logger. Anomalies go to {@value StartupAnomalies#LOGGER_NAME}, one WARNING record each, when they happen.
 */
public final class StartupRecorder {

    /** The logger of the report, and of the warning a failed boot logs. */
    public static final String LOGGER_NAME = "io.vidocq.startup";
    /** The {@linkplain Anomaly#source() source} of the anomalies the core detects. */
    public static final String CORE = "core";

    private static final System.Logger LOG = System.getLogger(LOGGER_NAME);

    private final List<Phase> phases = new ArrayList<>();
    private final List<Anomaly> anomalies = new ArrayList<>();
    private final List<Long> onStart = new ArrayList<>();
    private String phase;
    private long phaseStart;
    private String step;
    private String weaving;

    /** Starts timing {@code phase}, which is also what a failure is reported in until {@link #step} says more. */
    public void begin(String phase) {
        this.phase = phase;
        this.step = null;
        this.phaseStart = System.nanoTime();
    }

    /**
     * Names what the current phase is doing, such as {@code onStart chappe-bootstrap}, for a failure;
     * {@code null} names the phase alone again.
     */
    public void step(String step) {
        this.step = step;
    }

    /** Ends the current phase and keeps its duration. */
    public void end() {
        if (phase != null) {
            phases.add(new Phase(phase, System.nanoTime() - phaseStart));
        }
        phase = null;
        step = null;
    }

    /** What the boot was doing when it failed: the current step, else the current phase, else {@code boot}. */
    public String failing() {
        return step != null ? step : phase != null ? phase : "boot";
    }

    /** Keeps how long the next extension's {@code onStart} took, in the order the extensions start. */
    public void onStart(long nanos) {
        onStart.add(nanos);
    }

    /** The {@code onStart} durations, one per extension started, in order. */
    public List<Long> onStartNanos() {
        return List.copyOf(onStart);
    }

    /** Keeps what the load-time weaving did, as the {@code layer} section prints it. */
    public void weaving(String weaving) {
        this.weaving = weaving;
    }

    /** What the load-time weaving did, or {@code null} before it ran. */
    public String weaving() {
        return weaving;
    }

    /** Logs a core anomaly at once and keeps it for the report. */
    public void anomaly(String code, String message) {
        anomaly(code, message, null, CORE);
    }

    /**
     * Logs an anomaly at once, {@code [code] message}, then its hint, as one WARNING record of
     * {@value StartupAnomalies#LOGGER_NAME}, and keeps it for the report's {@code anomalies} section.
     *
     * @param code    the code
     * @param message what is broken and its probable cause
     * @param hint    how to fix it, or {@code null}
     * @param source  {@value #CORE}, or the id of a contributor
     */
    public void anomaly(String code, String message, String hint, String source) {
        anomaly(code, message, hint, source, null);
    }

    /**
     * {@link #anomaly(String, String, String, String)}, the WARNING record carrying the stack trace of
     * {@code thrown}.
     *
     * @param thrown what caused the anomaly, or {@code null}
     */
    public void anomaly(String code, String message, String hint, String source, Throwable thrown) {
        String text = hint == null ? message : message + " " + hint;
        if (thrown == null) {
            StartupAnomalies.warn(code, text);
        } else {
            StartupAnomalies.warn(code, text, thrown);
        }
        synchronized (anomalies) {
            anomalies.add(new Anomaly(code, message, hint, source));
        }
    }

    /** The anomalies reported so far, in order. */
    public List<Anomaly> anomalies() {
        synchronized (anomalies) {
            return List.copyOf(anomalies);
        }
    }

    /** The phases that ended, in order. */
    public List<Phase> phases() {
        return List.copyOf(phases);
    }

    /**
     * The report of this boot as it stands.
     *
     * @param launchMode   the launch mode
     * @param launchReason why, as the header prints it, or {@code null}
     * @param verbosity    the level of the report
     * @param runtime      the Vidocq version and the JVM, or {@code null}
     * @param sections     the sections, in order
     * @param failedPhase  what failed, or {@code null} when the boot succeeded
     */
    public StartupReport report(LaunchMode launchMode, String launchReason, Verbosity verbosity, String runtime,
                                List<Section> sections, String failedPhase) {
        return new StartupReport(launchMode, launchReason, verbosity, phases(), sections, anomalies(), failedPhase,
                runtime);
    }

    /** Logs {@code report} as one INFO record, unless its level is {@code off}. */
    public static void log(StartupReport report) {
        String text = StartupReportRenderer.render(report);
        if (!text.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, text);
        }
    }

    /** Logs, as a WARNING with its stack trace, that the report of a boot that went well could not be produced. */
    public static void skipped(Throwable cause) {
        LOG.log(System.Logger.Level.WARNING, "Startup report skipped: " + cause, cause);
    }

    /**
     * Logs one WARNING record for a boot that failed: the phase, the time spent, the anomalies already logged
     * and, in a dev launch, the partial report.
     *
     * @param partial      the report of the failed boot
     * @param elapsedNanos the time since the boot began
     * @param failure      what the boot threw, rethrown by the caller
     */
    public static void logFailure(StartupReport partial, long elapsedNanos, Throwable failure) {
        String text = StartupReportRenderer.failure(partial, elapsedNanos, failure.getClass().getName());
        if (partial.launchMode() == LaunchMode.DEV && partial.verbosity() != Verbosity.OFF) {
            text += "\n" + StartupReportRenderer.render(partial);
        }
        LOG.log(System.Logger.Level.WARNING, text);
    }
}
