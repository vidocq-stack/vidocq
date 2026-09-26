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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Where a {@link DevConsolePanel} writes its live values, provided by the dev console on every poll. Every method
 * returns a scope, so that calls chain: the scope it wrote to, the panel's own or a {@linkplain #group group}'s, and
 * for {@code group} the scope of that group.
 *
 * <p>A value is a key and one of these kinds, each shown its own way when no {@link Chart} plots it:
 * <ul>
 *   <li>a {@linkplain #gauge(String, double, Unit) gauge}, a level that goes up and down, such as the heap in use:
 *       its number, and a fill bar when it has a {@linkplain #gauge(String, double, double, Unit) max};</li>
 *   <li>a {@linkplain #counter counter}, a total that only grows during a boot, such as the borrows so far: its
 *       total, and how much it grew since the previous poll;</li>
 *   <li>a {@linkplain #duration duration}, such as a mean borrow time: formatted;</li>
 *   <li>a {@linkplain #text text}, such as a state: as it is;</li>
 *   <li>an {@linkplain #absent absent} value, greyed, with the reason it has none, such as
 *       {@code leak detection off}: never a zero that would read as a measure;</li>
 *   <li>a {@linkplain #table table}.</li>
 * </ul>
 *
 * <p><b>Keys.</b> A key follows the rule of {@link #requireKey}: a lowercase letter, then up to 39 lowercase letters,
 * digits, dots or hyphens, such as {@code heap.used} or {@code mean-borrow}. Any other key throws
 * {@link IllegalArgumentException}, which fails the sample, so that the mistake shows at once in development. The same
 * key written twice in one scope keeps the last value.
 *
 * <p><b>Groups.</b> A group is one named item among several of the same kind, such as a connection pool or a garbage
 * collector: {@code out.group("audit").gauge("active", 3, 8, Unit.COUNT)}. Each chart of the panel is repeated for
 * every group that holds at least one of its keys. Groups hold values only: they do not nest.
 *
 * <p><b>What the console does with it.</b> It renders every string as text, never as markup, with its control
 * characters replaced and cut at 200 characters. It keeps 64 groups, 64 values per scope and 100 rows of 8 columns
 * per table, and drops the rest, flagging the sample {@code truncated}. It stamps each poll with its own clock: a
 * panel never adds a timestamp or computes a rate, so that a missed poll or a paused debugger does no harm.
 *
 * <p>A sample is valid only during the {@link DevConsolePanel#sample sample} call that received it, on the thread
 * of that call: a panel never keeps it.
 */
public interface PanelSample {

    /**
     * The header of a {@link #table table} column the page turns into a "Replay" button: each cell is the id of an
     * action of the same panel, a space, then a JSON object of its arguments by name, such as
     * {@code tool.weather {"arguments":{"city":"Paris"}}}. The button fills that action's form with them and sends
     * nothing; a value {@code "***"} is left empty for the user to type. The console keeps such a cell whole up to
     * {@value #MAX_REPLAY_CELL} characters, and empties a longer one.
     */
    String REPLAY_COLUMN = "replay";

    /** The longest cell of a {@value #REPLAY_COLUMN} column the console keeps. */
    int MAX_REPLAY_CELL = 4096;

    /**
     * A level that goes up and down, such as the threads alive. A value that is not a number, or is infinite, is
     * shown as absent.
     *
     * @param key   the key of the value
     * @param value the level
     * @param unit  what it measures; never {@code null}
     * @return this scope
     */
    PanelSample gauge(String key, double value, Unit unit);

    /**
     * A level with the most it can reach, such as the active connections of a pool of 8: shown with a fill bar, and
     * drawn as a dashed rule by a {@link Series.Style#CEILING CEILING} series. A max that is not a finite number
     * above zero is left out.
     *
     * @param key   the key of the value
     * @param value the level
     * @param max   the most it can reach, in the same unit
     * @param unit  what both measure, such as {@link Unit#RATIO} with a max of 1 for a load; never {@code null}
     * @return this scope
     */
    PanelSample gauge(String key, double value, double max, Unit unit);

    /**
     * A total that only grows during a boot, such as the borrows so far, as the component keeps it: a panel never
     * resets it. The page shows how much it grew since the previous poll, and a {@link Series.Style#RATE RATE}
     * series plots that growth per second. A total lower than the previous one reads as a count that started
     * again: the rate has a gap there.
     *
     * @param key   the key of the value
     * @param total the total so far
     * @param unit  what it counts; {@link Unit#NANOS} for time spent; never {@code null}
     * @return this scope
     */
    PanelSample counter(String key, long total, Unit unit);

    /**
     * A duration, such as a mean over the whole boot: formatted, and never plotted.
     *
     * @param key   the key of the value
     * @param value the duration, or {@code null} for an absent one
     * @return this scope
     */
    PanelSample duration(String key, Duration value);

    /**
     * A short text, such as a state or a mode.
     *
     * @param key   the key of the value
     * @param value the text, or {@code null} for an absent one
     * @return this scope
     */
    PanelSample text(String key, String value);

    /**
     * A value this panel cannot give now, and why, such as {@code leaks} with {@code leak detection off}, or a
     * figure the JVM does not publish: shown greyed, never as a zero.
     *
     * @param key    the key of the value
     * @param reason why it is absent, or {@code null} for no reason
     * @return this scope
     */
    PanelSample absent(String key, String reason);

    /**
     * A small table, such as the entries of a registry. A row with fewer cells than there are columns is completed
     * with empty cells, one with more is cut; a {@code null} cell is empty.
     *
     * @param key     the key of the value
     * @param columns the column headers, from left to right; never {@code null}
     * @param rows    the rows, from top to bottom, each a list of cells from left to right; never {@code null}
     * @return this scope
     */
    PanelSample table(String key, List<String> columns, List<List<String>> rows);

    /**
     * The scope of one named item, such as a pool or a garbage collector, created by the first call with that name
     * and returned again by the next ones. Groups keep the order of their first call. Called on the scope of a group,
     * it throws {@link IllegalStateException}: groups do not nest.
     *
     * @param name what the page calls the item, such as {@code audit} or {@code G1 Young Generation}; neither
     *             {@code null} nor blank, else {@link IllegalArgumentException}
     * @return the scope of that group
     */
    PanelSample group(String name);

    /**
     * Checks a key against the rule every value key, {@link Series} key and {@link Chart} id follows: a lowercase
     * ASCII letter, then up to 39 lowercase ASCII letters, digits, dots or hyphens, the regular expression
     * {@code [a-z][a-z0-9.-]{0,39}}. The console checks every key a panel writes with it; a panel or a test may too.
     *
     * @param key the key, such as {@code heap.used}
     * @return the same key
     * @throws NullPointerException     when the key is {@code null}
     * @throws IllegalArgumentException when it breaks the rule
     */
    static String requireKey(String key) {
        Objects.requireNonNull(key, "key");
        int length = key.length();
        boolean valid = length >= 1 && length <= 40 && key.charAt(0) >= 'a' && key.charAt(0) <= 'z';
        for (int i = 1; valid && i < length; i++) {
            char c = key.charAt(i);
            valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
        }
        if (!valid) {
            throw new IllegalArgumentException("invalid key \"" + key + "\": a key matches [a-z][a-z0-9.-]{0,39}");
        }
        return key;
    }
}
