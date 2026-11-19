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
package io.vidocq.runtime.spi.devconsole;

import java.util.Objects;

/**
 * One value a {@link Chart} plots, and how: the key of a value the panel {@linkplain PanelSample samples}, and a
 * style. The page keeps the successive samples of that value, up to five minutes of them, and draws its history.
 *
 * <p>The factories read best in a chart: {@code List.of(Series.area("active"), Series.stacked("idle"))}.
 *
 * @param key   the key of a value in the same scope of the sample, a {@linkplain PanelSample#gauge gauge} or a
 *              {@linkplain PanelSample#counter counter} according to the style; it follows the rule of
 *              {@link PanelSample#requireKey}. A key the sample does not hold, or holds as another kind of value,
 *              draws nothing
 * @param style how the page draws it; never {@code null}
 */
public record Series(String key, Style style) {

    /** How a {@link Series} is drawn. */
    public enum Style {

        /** A gauge, filled down to zero: a level, such as the heap in use. */
        AREA,

        /**
         * A gauge, filled on top of the {@link #AREA} or {@code STACKED} series before it in the chart: one part of a
         * whole, such as the idle connections over the active ones.
         */
        STACKED,

        /** A gauge, as a line: a level that is not part of a whole, such as the borrowers waiting. */
        LINE,

        /**
         * A counter, as its growth per second between two polls, such as the borrows per second. For a counter of
         * {@linkplain Unit#NANOS nanoseconds}, the share of wall time spent. The page computes it from the time
         * the console stamps on each poll: a panel never computes a rate itself.
         */
        RATE,

        /**
         * The {@linkplain PanelSample#gauge(String, double, double, Unit) max} of a gauge, as a dashed rule: what
         * the level can reach, such as the size of a pool over its active connections.
         */
        CEILING
    }

    public Series {
        PanelSample.requireKey(key);
        Objects.requireNonNull(style, "style");
    }

    /**
     * A gauge filled down to zero.
     *
     * @param key the key of the gauge
     * @return the series
     */
    public static Series area(String key) {
        return new Series(key, Style.AREA);
    }

    /**
     * A gauge filled on top of the area or stacked series before it.
     *
     * @param key the key of the gauge
     * @return the series
     */
    public static Series stacked(String key) {
        return new Series(key, Style.STACKED);
    }

    /**
     * A gauge drawn as a line.
     *
     * @param key the key of the gauge
     * @return the series
     */
    public static Series line(String key) {
        return new Series(key, Style.LINE);
    }

    /**
     * The growth per second of a counter.
     *
     * @param key the key of the counter
     * @return the series
     */
    public static Series rate(String key) {
        return new Series(key, Style.RATE);
    }

    /**
     * The max of a gauge, as a dashed rule.
     *
     * @param key the key of the gauge
     * @return the series
     */
    public static Series ceiling(String key) {
        return new Series(key, Style.CEILING);
    }
}
