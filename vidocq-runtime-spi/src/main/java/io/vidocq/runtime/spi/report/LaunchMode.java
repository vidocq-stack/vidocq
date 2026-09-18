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
package io.vidocq.runtime.spi.report;

import java.util.Locale;
import java.util.Optional;

/**
 * How this JVM was launched, as Vidocq reads it once per boot from the signals of the launch:
 * {@code vidocq.launch.mode}, a {@code vidocq.profile} that names a mode, the {@code vidocq:dev}
 * reload loop, a test framework on the booting thread, an application loaded from a build tree.
 *
 * <p>It is an observation, not a setting: it decides what the startup banner and the startup report
 * show by default, and an extension reads it through
 * {@link io.vidocq.runtime.spi.ExtensionContext#launchMode()} or
 * {@link StartupReportContext#launchMode()} to offer what only makes sense in development. The
 * container, the configuration and the extensions' lifecycle are the same in every mode.
 */
public enum LaunchMode {

    /** A development launch: {@code vidocq dev}, an IDE Run, a build tree. */
    DEV,
    /** A test run: JUnit, TestNG, Surefire, Arquillian. */
    TEST,
    /** Everything else: a packaged application, a container, CI. */
    PROD;

    /**
     * The name as it is written in {@code vidocq.launch.mode} and on the banner's context line:
     * {@code dev}, {@code test} or {@code prod}.
     */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The mode named by {@code value}, ignoring case and surrounding blanks.
     *
     * @param value a {@link #label() label} such as {@code " Dev "}, or {@code null}
     * @return the mode, or empty when {@code value} is {@code null}, blank or names no mode
     */
    public static Optional<LaunchMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String name = value.strip().toLowerCase(Locale.ROOT);
        for (LaunchMode mode : values()) {
            if (mode.label().equals(name)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
