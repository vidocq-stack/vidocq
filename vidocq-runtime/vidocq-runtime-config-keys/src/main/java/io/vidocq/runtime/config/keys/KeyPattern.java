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
package io.vidocq.runtime.config.keys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The pattern of a configuration key, as a file of key descriptions writes it: literal segments, names the
 * application chooses and, last, a free tail.
 *
 * <ul>
 *   <li>{@code <family>} is one segment the application chooses, a name of that family:
 *       {@code vidocq.chappe.listener.<listener>.port};</li>
 *   <li>{@code [<family>]} is the same, and the segment may be absent: {@code vidocq.pool.[<datasource>].url} is
 *       {@code vidocq.pool.url} and {@code vidocq.pool.audit.url};</li>
 *   <li>a final {@code *} stands for one segment or more: {@code vidocq.persistence.<unit>.properties.*}.</li>
 * </ul>
 *
 * <p>In a key, a name that holds a dot is written between double quotes
 * ({@code vidocq.persistence."my.unit".datasource}); a quoted segment is a name, never a literal.
 */
public final class KeyPattern {

    private static final Pattern FAMILY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern LITERAL = Pattern.compile("[^<>\\[\\]*|\"\\s]+");

    private sealed interface Segment permits Literal, Name, Tail {}

    private record Literal(String text) implements Segment {}

    private record Name(String family, boolean optional) implements Segment {}

    private record Tail() implements Segment {}

    /** A segment of a key: its text, and whether it was written between double quotes. */
    private record Part(String text, boolean quoted) {}

    private final String text;
    private final List<Segment> segments;

    private KeyPattern(String text, List<Segment> segments) {
        this.text = text;
        this.segments = segments;
    }

    /**
     * Reads a pattern.
     *
     * @param text the pattern, as a file writes it
     * @return the pattern
     * @throws IllegalArgumentException when the text is not a pattern; the message quotes the text and says why
     */
    public static KeyPattern parse(String text) {
        if (text == null || text.isEmpty()) {
            throw refused(text == null ? "" : text, "it is empty");
        }
        String[] parts = text.split("\\.", -1);
        List<Segment> segments = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            segments.add(segment(text, parts[i], i == parts.length - 1));
        }
        if (!(segments.getFirst() instanceof Literal)) {
            throw refused(text, "its first segment is a name or a tail, and a key starts with a literal segment");
        }
        return new KeyPattern(text, List.copyOf(segments));
    }

    private static Segment segment(String pattern, String part, boolean last) {
        if (part.isEmpty()) {
            throw refused(pattern, "it has an empty segment");
        }
        if (part.equals("*")) {
            if (!last) {
                throw refused(pattern, "'*' is only the last segment");
            }
            return new Tail();
        }
        if (part.startsWith("[")) {
            if (part.length() > 4 && part.startsWith("[<") && part.endsWith(">]")) {
                return new Name(family(pattern, part.substring(2, part.length() - 2)), true);
            }
            throw refused(pattern, "the segment '" + part + "' is not an optional name, written [<family>]");
        }
        if (part.startsWith("<")) {
            if (part.length() > 2 && part.endsWith(">")) {
                return new Name(family(pattern, part.substring(1, part.length() - 1)), false);
            }
            throw refused(pattern, "the segment '" + part + "' is not a name, written <family>");
        }
        if (!LITERAL.matcher(part).matches()) {
            throw refused(pattern, "the segment '" + part + "' holds a character a key segment cannot hold");
        }
        return new Literal(part);
    }

    private static String family(String pattern, String family) {
        if (!FAMILY.matcher(family).matches()) {
            throw refused(pattern, "'" + family + "' is not the name of a family: letters, digits, '-' and '_'");
        }
        return family;
    }

    private static IllegalArgumentException refused(String pattern, String why) {
        return new IllegalArgumentException("'" + pattern + "' is not a key pattern: " + why);
    }

    /** The pattern as it was written. */
    public String text() {
        return text;
    }

    /** The families the pattern names, each once, in the order of their first segment. */
    public List<String> families() {
        Set<String> families = new LinkedHashSet<>();
        for (Segment segment : segments) {
            if (segment instanceof Name name) {
                families.add(name.family());
            }
        }
        return List.copyOf(families);
    }

    /**
     * The pattern of a key next to this one: the same segments, the last one replaced.
     *
     * @param last the last segment of the sibling
     * @return the sibling's pattern
     * @throws IllegalArgumentException when {@code last} cannot be a segment
     */
    public KeyPattern sibling(String last) {
        int dot = text.lastIndexOf('.');
        return parse(text.substring(0, dot + 1) + last);
    }

    /**
     * How a key matches this pattern.
     *
     * @param key a configuration key, as an application writes it
     * @return the match, or empty when the key does not match; never throws, whatever the key
     */
    public Optional<Match> match(String key) {
        List<Part> parts = split(key);
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> names = new LinkedHashMap<>();
        return match(0, parts, 0, names, 0);
    }

    private Optional<Match> match(int at, List<Part> parts, int part, Map<String, String> names, int literals) {
        if (at == segments.size()) {
            return part == parts.size() ? Optional.of(new Match(names, literals, false)) : Optional.empty();
        }
        return switch (segments.get(at)) {
            case Tail _ -> part < parts.size() ? Optional.of(new Match(names, literals, true)) : Optional.empty();
            case Literal literal -> part < parts.size() && !parts.get(part).quoted()
                    && parts.get(part).text().equals(literal.text())
                    ? match(at + 1, parts, part + 1, names, literals + 1)
                    : Optional.empty();
            case Name name -> named(name, at, parts, part, names, literals);
        };
    }

    /** A name takes the segment when the rest then matches; an optional one is otherwise taken as absent. */
    private Optional<Match> named(Name name, int at, List<Part> parts, int part, Map<String, String> names,
            int literals) {
        if (part < parts.size()) {
            Map<String, String> bound = new LinkedHashMap<>(names);
            bound.putIfAbsent(name.family(), parts.get(part).text());
            Optional<Match> present = match(at + 1, parts, part + 1, bound, literals);
            if (present.isPresent()) {
                return present;
            }
        }
        return name.optional() ? match(at + 1, parts, part, names, literals) : Optional.empty();
    }

    /** The segments of a key, split on the dots outside double quotes; empty when the key is not well formed. */
    private static List<Part> split(String key) {
        if (key == null || key.isEmpty()) {
            return List.of();
        }
        List<Part> parts = new ArrayList<>();
        int at = 0;
        while (at <= key.length()) {
            int end;
            if (at < key.length() && key.charAt(at) == '"') {
                int quote = key.indexOf('"', at + 1);
                if (quote < 0 || quote == at + 1 || (quote + 1 < key.length() && key.charAt(quote + 1) != '.')) {
                    return List.of();
                }
                parts.add(new Part(key.substring(at + 1, quote), true));
                end = quote + 1;
            } else {
                end = key.indexOf('.', at);
                end = end < 0 ? key.length() : end;
                if (end == at) {
                    return List.of();
                }
                parts.add(new Part(key.substring(at, end), false));
            }
            at = end + 1;
        }
        return parts;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyPattern pattern && pattern.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }

    /**
     * How a key matches a pattern. Matches order best first: a pattern that describes the key before one that
     * only covers it with a tail, then the one with more literal segments, so that a literal wins over a name.
     *
     * @param names    the name the key gives each family of the pattern; a family absent from the key is absent here
     * @param literals how many literal segments of the pattern the key matched
     * @param tail     whether the end of the key was matched by the pattern's final {@code *}
     */
    public record Match(Map<String, String> names, int literals, boolean tail) implements Comparable<Match> {

        public Match {
            names = Map.copyOf(names);
        }

        @Override
        public int compareTo(Match other) {
            int byTail = Boolean.compare(tail, other.tail);
            return byTail != 0 ? byTail : Integer.compare(other.literals, literals);
        }
    }
}
