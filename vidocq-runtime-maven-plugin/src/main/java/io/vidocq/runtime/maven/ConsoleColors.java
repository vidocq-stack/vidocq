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
package io.vidocq.runtime.maven;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether the JVM that {@code vidocq:run} and {@code vidocq:dev} fork may colour its output, decided
 * from what Maven decided for its own.
 *
 * <p>The child inherits Maven's streams ({@code redirectErrorStream} + {@code inheritIO}), so its
 * console policy should be Maven's. It cannot find that out by itself: under an IDE's Maven console —
 * the case this exists for (Vidocq/vidocq#83) — there is no TTY and no {@code idea_rt.jar} agent, so
 * the runtime's {@code vidocq.console.color=auto} turns colours off while Maven's own {@code [INFO]}
 * lines stay coloured. This class answers the question for it, and the mojos pass the answer as
 * {@code -Dvidocq.console.color}.
 *
 * <p>The signal is the {@code jansi.mode} system property: {@code MavenCli.logging()} calls
 * {@code MessageUtils.setColorEnabled(flag)}, which sets it to {@code force} or {@code strip}
 * (maven-shared-utils, unchanged from 3.3.4 to 3.4.2). It is the only signal that catches every way of
 * asking — {@code -Dstyle.color}, {@code --color} and {@code -B} alike — and an IDE Maven run always
 * lands on {@code force} (IntelliJ 2026.2 passes {@code -Djansi.passthrough=true -Dstyle.color=always}).
 * When it is unset, which is Maven 4 or a plain {@code auto} run, the {@code style.color} user property
 * answers instead; when that is unset too nothing is passed and the runtime decides for itself, as it
 * does outside Maven.
 *
 * <p>Two things always win: an explicit {@code vidocq.console.color}, which is never overwritten, and
 * {@code NO_COLOR}, which the child honours on its own and which stops the decision here.
 */
public final class ConsoleColors {

    /** Configuration key of the runtime's colour policy ({@code ConsoleSupport.COLOR_KEY}). */
    public static final String COLOR_KEY = "vidocq.console.color";

    /** Maven's own colour switch, read by {@code MavenCli} (3.9.16, line 435). */
    static final String STYLE_COLOR = "style.color";

    /** Set by {@code MessageUtils.setColorEnabled} to {@code force} or {@code strip}. */
    static final String JANSI_MODE = "jansi.mode";

    /**
     * What to pass to the child, and why — the reason is logged once, at debug level.
     *
     * @param mode   {@code always} or {@code never}, a value of {@code ConsoleSupport.ColorMode}
     * @param reason what Maven's output does, and what said so
     */
    public record Choice(String mode, String reason) {

        /** The line the mojos log. */
        public String logLine() {
            return reason + " — the forked JVM gets -D" + COLOR_KEY + "=" + mode;
        }
    }

    private ConsoleColors() {
    }

    /**
     * The decision for this Maven JVM, or empty when nothing should be passed.
     *
     * @param alreadySet whether the child already carries {@value #COLOR_KEY}, which is then left alone
     */
    public static Optional<Choice> forChild(boolean alreadySet) {
        return forChild(alreadySet || System.getProperty(COLOR_KEY) != null, System.getProperty(JANSI_MODE),
                System.getProperty(STYLE_COLOR), System.getenv("NO_COLOR"));
    }

    /**
     * The decision from its raw inputs.
     *
     * @param alreadySet  whether {@value #COLOR_KEY} is already set, here or for the child
     * @param jansiMode   the {@value #JANSI_MODE} system property Maven set, or {@code null}
     * @param styleColor  the {@value #STYLE_COLOR} user property, or {@code null}
     * @param noColor     the {@code NO_COLOR} environment variable, or {@code null}
     */
    static Optional<Choice> forChild(boolean alreadySet, String jansiMode, String styleColor, String noColor) {
        if (alreadySet) {
            return Optional.empty();
        }
        if (noColor != null && !noColor.isEmpty()) {
            // The child reads NO_COLOR itself and it wins over every mode: saying it again would only
            // hide why its output has no colour.
            return Optional.empty();
        }
        String mode = normalize(jansiMode);
        if ("force".equals(mode)) {
            return colored(true, JANSI_MODE + "=force");
        }
        if ("strip".equals(mode)) {
            return colored(false, JANSI_MODE + "=strip");
        }
        // Maven 4 drops jansi for JLine, and a plain `auto` run never sets the property: fall back to the
        // switch MavenCli itself reads. It misses the `--color <arg>` option, which jansi.mode catches.
        String style = normalize(styleColor);
        return switch (style) {
            case "always", "yes", "force" -> colored(true, STYLE_COLOR + "=" + style);
            case "never", "no", "none" -> colored(false, STYLE_COLOR + "=" + style);
            default -> Optional.empty();
        };
    }

    private static Optional<Choice> colored(boolean colored, String because) {
        return Optional.of(new Choice(colored ? "always" : "never",
                "Maven's own output is " + (colored ? "coloured" : "not coloured") + " (" + because + ")"));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
