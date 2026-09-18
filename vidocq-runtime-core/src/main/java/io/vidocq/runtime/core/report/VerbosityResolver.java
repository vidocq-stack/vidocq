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

import java.util.Locale;
import java.util.Optional;

/**
 * How much the startup report shows on a boot: {@value #KEY}, then the launch.
 *
 * <p>The decision is a pure function of the configured value, the {@link LaunchMode}, whether the boot is
 * an embedded deployment and whether it is the first boot of the JVM:
 * <ul>
 *   <li>{@code off}, {@code summary} or {@code detailed} is honoured on every boot, whatever the launch;</li>
 *   <li>{@code auto}, the default, gives nothing to an embedded deployment (Arquillian, the TCK, which boot
 *       hundreds of times), {@code detailed} to the first dev boot of the JVM and {@code summary} to the
 *       reloads of the dev loop, and {@code summary} in {@code test} and in {@code prod}.</li>
 * </ul>
 * A value it does not accept reads as {@code auto}; the bootstrap reports it once per boot, as
 * {@value StartupAnomalies#INVALID_VALUE}, after checking it with {@link #isSetting(String)}.
 */
public final class VerbosityResolver {

    /** Configuration key of the report level: {@code auto}, {@code off}, {@code summary} or {@code detailed}. */
    public static final String KEY = "vidocq.startup.report";
    /** The values {@value #KEY} accepts, as they are written. */
    public static final String ACCEPTED = "auto, off, summary, detailed";
    /** The value that lets the launch decide. */
    static final String AUTO = "auto";

    private VerbosityResolver() {}

    /**
     * The level of a boot.
     *
     * @param configured         {@value #KEY}, or {@code null}; blank, {@code auto} and invalid values mean
     *                           {@code auto}
     * @param launchMode         the resolved launch mode, {@code null} reading as {@code prod}
     * @param embeddedDeployment {@code VidocqBootstrap.configure(List)}: Arquillian, the TCK
     * @param firstBoot          whether no boot happened before in this JVM: the dev reload loop boots
     *                           again, and only its first boot is detailed
     */
    public static Verbosity resolve(String configured, LaunchMode launchMode, boolean embeddedDeployment,
                                    boolean firstBoot) {
        Optional<Verbosity> explicit = explicit(configured);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        if (embeddedDeployment) {
            return Verbosity.OFF;
        }
        return launchMode == LaunchMode.DEV && firstBoot ? Verbosity.DETAILED : Verbosity.SUMMARY;
    }

    /**
     * Whether {@code value} is a value of {@value #KEY}: unset, blank, {@code auto} or a level, ignoring
     * case and surrounding blanks.
     */
    public static boolean isSetting(String value) {
        return value == null || value.isBlank() || normalized(value).equals(AUTO) || explicit(value).isPresent();
    }

    /** The level {@code value} names, empty for {@code auto} and for anything that names none. */
    static Optional<Verbosity> explicit(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String name = normalized(value);
        for (Verbosity verbosity : Verbosity.values()) {
            if (verbosity.name().toLowerCase(Locale.ROOT).equals(name)) {
                return Optional.of(verbosity);
            }
        }
        return Optional.empty();
    }

    private static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
