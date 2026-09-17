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
package io.vidocq.runtime.core.banner;

import java.util.Locale;
import java.util.Optional;

/**
 * How this JVM was launched, as {@link LaunchModeResolver} reads it from the signals of the launch.
 *
 * <p>It is an observation, not a setting: nothing in the runtime behaves differently because of it.
 * It is printed so that a log or a screenshot says which of the three it was.
 */
public enum LaunchMode {

    /** A development launch: {@code vidocq dev}, an IDE Run, a build tree. */
    DEV,
    /** A test run: JUnit, TestNG, Surefire, Arquillian. */
    TEST,
    /** Everything else: a packaged application, a container, CI. */
    PROD;

    /** The name as it is written in {@code vidocq.launch.mode} and on the context line. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The mode named by {@code value}, ignoring case and surrounding blanks; empty when it names none. */
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
