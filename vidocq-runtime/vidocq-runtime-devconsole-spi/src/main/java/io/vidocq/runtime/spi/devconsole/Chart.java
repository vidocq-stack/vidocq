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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A chart the page draws from the successive samples of a {@link DevConsolePanel}, as the panel's
 * {@link DevConsolePanel#charts() charts()} lists them. Checked when it is built, so that a mistake fails where it is
 * written rather than on the page.
 *
 * <p>A chart plots the values of the panel's own scope, and is repeated once per
 * {@linkplain PanelSample#group group} that holds at least one of its keys, such as once per connection pool:
 * <pre>{@code
 * new Chart("connections", "Connections", List.of(
 *         Series.area("active"), Series.stacked("idle"), Series.line("waiting"), Series.ceiling("active")))
 * }</pre>
 *
 * <p>Plot what changes between two polls: a level, or the rate of a counter. A mean over the whole boot moves less
 * and less as the boot gets older, so its curve would say nothing of the last minutes: sample it as a
 * {@linkplain PanelSample#duration duration} and let the page show it as a number.
 *
 * @param id     identifies the chart among those of its panel, stable across boots; it follows the rule of
 *               {@link PanelSample#requireKey}, such as {@code connections}
 * @param title  what the page prints over the chart, such as {@code Connections}; neither {@code null} nor blank
 * @param series what it plots, in drawing order, at least one, never the same key twice in the same style; a
 *               {@link Series.Style#STACKED STACKED} series needs an {@link Series.Style#AREA AREA} or
 *               {@code STACKED} series before it. An immutable copy
 */
public record Chart(String id, String title, List<Series> series) {

    public Chart {
        PanelSample.requireKey(id);
        Objects.requireNonNull(title, "title");
        if (title.isBlank()) {
            throw new IllegalArgumentException("chart '" + id + "' has a blank title");
        }
        series = List.copyOf(Objects.requireNonNull(series, "series"));
        if (series.isEmpty()) {
            throw new IllegalArgumentException("chart '" + id + "' plots no series");
        }
        Set<Series> seen = new HashSet<>();
        boolean filled = false;
        for (Series s : series) {
            if (!seen.add(s)) {
                throw new IllegalArgumentException("chart '" + id + "' plots " + s.key() + " twice as "
                        + s.style());
            }
            if (s.style() == Series.Style.STACKED && !filled) {
                throw new IllegalArgumentException("chart '" + id + "' stacks " + s.key()
                        + " on nothing: an AREA or STACKED series must come before it");
            }
            filled |= s.style() == Series.Style.AREA || s.style() == Series.Style.STACKED;
        }
    }
}
