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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One file of key descriptions, read: {@code META-INF/vidocq/config-user-keys.properties}, which the author of
 * a jar writes, or {@code META-INF/vidocq/config-keys.properties}, which a build generates.
 *
 * <p>A file is a list of facts, one per line: {@code <key pattern>|<attribute>=<value>}, after a first fact
 * {@code format=1}. A line on a family, {@code <family>|names=...}, gives the names that exist without being
 * written in the application's configuration. Blank lines and lines starting with {@code #} or {@code !} are
 * skipped. The file is UTF-8 and holds no backslash, so no escape and no continuation line, and the part of a
 * line before its {@code =} holds no white space and no colon: {@code java.util.Properties} reads the same keys
 * and the same values, but for the white space at the end of a value, which this reader drops.
 *
 * <p>Reading never fails on what a file holds: what is wrong becomes a {@link Problem} and the rest is read.
 *
 * @param origin   the name the reader was given for the file, repeated in its problems
 * @param keys     the keys the file describes, in the order it first names them
 * @param families the families the file says something of, in the order it first names them
 * @param problems what is wrong in the file, in the order of its lines
 */
public record KeyDescriptions(String origin, List<KeyDescription> keys, List<KeyFamily> families,
        List<Problem> problems) {

    /** The version of the format this reader knows. */
    public static final int FORMAT = 1;

    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private static final KeyDescriptions NONE = new KeyDescriptions("", List.of(), List.of(), List.of());

    public KeyDescriptions {
        keys = List.copyOf(keys);
        families = List.copyOf(families);
        problems = List.copyOf(problems);
    }

    /** What a jar with no such file describes: nothing. */
    public static KeyDescriptions none() {
        return NONE;
    }

    /**
     * Reads a file.
     *
     * @param origin how to name the file in its problems: its path, or the jar and its path
     * @param in     the content of the file, in UTF-8; not closed
     * @return what the file describes and what is wrong in it
     * @throws IOException when the stream cannot be read
     */
    public static KeyDescriptions read(String origin, InputStream in) throws IOException {
        return read(origin, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Reads the text of a file.
     *
     * @param origin how to name the file in its problems
     * @param text   the content of the file
     * @return what the file describes and what is wrong in it
     */
    public static KeyDescriptions read(String origin, String text) {
        return new Reading(origin).read(text);
    }

    /** The facts of one key or of one family. */
    private static final class Facts {

        private final Map<Attribute, Fact> kept = new EnumMap<>(Attribute.class);

        private String value(Attribute attribute) {
            Fact fact = kept.get(attribute);
            return fact == null ? null : fact.value();
        }
    }

    /** The reading of one file. */
    private static final class Reading {

        private static final Pattern FAMILY_LINE = Pattern.compile("<([A-Za-z0-9_-]+)>");
        private static final Pattern FAMILY = Pattern.compile("[A-Za-z0-9_-]+");
        private static final Pattern ATTRIBUTE = Pattern.compile("[a-z][a-z0-9-]*");
        private static final Pattern BREAKS_A_PROPERTIES_KEY = Pattern.compile("[\\s:]");
        private static final Pattern SEGMENT = Pattern.compile("[^<>\\[\\]*|\"\\s.=]+");
        private static final Pattern JAVA_TYPE = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");
        private static final Set<String> TYPES = Set.of("string", "boolean", "int", "long", "double", "duration");
        private static final String LIST = "list:";

        private final String origin;
        private final List<Problem> problems = new ArrayList<>();
        private final Map<KeyPattern, Facts> keys = new LinkedHashMap<>();
        private final Map<String, Facts> families = new LinkedHashMap<>();

        Reading(String origin) {
            this.origin = origin;
        }

        KeyDescriptions read(String text) {
            String[] lines = (text.startsWith(BYTE_ORDER_MARK) ? text.substring(1) : text).split("\r\n|\r|\n", -1);
            boolean first = true;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                boolean newer = fact(i + 1, line, first);
                first = false;
                if (newer) {
                    return new KeyDescriptions(origin, List.of(), List.of(), problems);
                }
            }
            if (first) {
                wrong(0, "the file says nothing: its first fact is format=" + FORMAT);
            }
            return new KeyDescriptions(origin, descriptions(), familyDescriptions(), problems);
        }

        /** Reads one fact; answers whether it says the file is of a format this reader cannot read. */
        private boolean fact(int number, String line, boolean first) {
            if (line.indexOf('\\') >= 0) {
                wrong(number, "a backslash cannot be written: the format has no escape and no continuation line");
                return false;
            }
            int equals = line.indexOf('=');
            String left = equals < 0 ? line : line.substring(0, equals).strip();
            String value = equals < 0 ? null : line.substring(equals + 1).strip();
            if (left.equals("format") && value != null) {
                return format(number, value, first);
            }
            if (first) {
                wrong(number, "the first fact of the file is format=" + FORMAT);
            }
            int bar = left.indexOf('|');
            if (value == null || bar <= 0 || bar == left.length() - 1) {
                wrong(number, "'" + line + "' is not a fact, written <key>|<attribute>=<value>");
                return false;
            }
            if (BREAKS_A_PROPERTIES_KEY.matcher(left).find()) {
                wrong(number, "'" + left + "' holds a white space or a colon, where a .properties file ends its key");
                return false;
            }
            String key = left.substring(0, bar);
            String name = left.substring(bar + 1);
            if (!ATTRIBUTE.matcher(name).matches()) {
                wrong(number, "'" + name + "' cannot be an attribute, written in lower case: '" + line
                        + "' is not a fact, written <key>|<attribute>=<value>");
                return false;
            }
            Optional<Attribute> attribute = Attribute.of(name);
            Matcher family = FAMILY_LINE.matcher(key);
            if (family.matches()) {
                Facts facts = families.computeIfAbsent(family.group(1), f -> new Facts());
                attribute.ifPresentOrElse(known -> family(number, family.group(1), facts, known, value),
                        () -> newer(number, name));
                return false;
            }
            KeyPattern pattern;
            try {
                pattern = KeyPattern.parse(key);
            } catch (IllegalArgumentException e) {
                wrong(number, e.getMessage());
                return false;
            }
            // The key exists from the first fact that names it, even one this reader skips: a reader that
            // dropped it would later take the key for one nobody describes.
            Facts facts = keys.computeIfAbsent(pattern, p -> new Facts());
            attribute.ifPresentOrElse(known -> key(number, key, facts, known, value), () -> newer(number, name));
            return false;
        }

        private void newer(int number, String attribute) {
            problems.add(new Problem(origin, number, Problem.Kind.NEWER,
                    "'" + attribute + "' is not an attribute of format " + FORMAT + ": the fact is skipped"));
        }

        private boolean format(int number, String value, boolean first) {
            if (!first) {
                wrong(number, "format is the first fact of a file, and is given once");
                return false;
            }
            int format;
            try {
                format = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                format = 0;
            }
            if (format < 1) {
                wrong(number, "format '" + value + "' is not a version of the format: a whole number from 1");
                return false;
            }
            if (format > FORMAT) {
                problems.add(new Problem(origin, number, Problem.Kind.NEWER, "format " + format
                        + " is newer than the format this reader knows (" + FORMAT + "): the file is not read"));
                return true;
            }
            return false;
        }

        private void family(int number, String family, Facts facts, Attribute attribute, String value) {
            if (attribute != Attribute.NAMES && attribute != Attribute.DOC) {
                wrong(number, "a family takes names and doc only, and <" + family + "> is given "
                        + attribute.text());
                return;
            }
            if (attribute == Attribute.NAMES && KeyDescription.list(value).isEmpty()) {
                wrong(number, "names of <" + family + "> is empty");
                return;
            }
            put(number, facts, "<" + family + ">", attribute, value);
        }

        private void key(int number, String key, Facts facts, Attribute attribute, String value) {
            if (attribute == Attribute.NAMES) {
                wrong(number, "names is said of a family, written <family>|names=..., and '" + key + "' is a key");
                return;
            }
            String refusal = refusal(attribute, value);
            if (refusal != null) {
                wrong(number, key + "|" + attribute.text() + ": " + refusal);
                return;
            }
            put(number, facts, key, attribute, value);
        }

        private void put(int number, Facts facts, String subject, Attribute attribute, String value) {
            Fact earlier = facts.kept.putIfAbsent(attribute, new Fact(value, origin, number));
            if (earlier != null) {
                wrong(number, subject + "|" + attribute.text() + " was already given on line " + earlier.line()
                        + ": the first is kept");
            }
        }

        /** Why a key cannot have this value for this attribute, or null when it can. */
        private static String refusal(Attribute attribute, String value) {
            return switch (attribute) {
                case TYPE -> isType(value, true) ? null : "'" + value + "' is not a type of the format (string,"
                        + " boolean, int, long, double, duration, list:<type>) nor a qualified Java type name";
                case REQUIRED, DEPRECATED -> value.equals("true") || value.equals("false") ? null
                        : "'" + value + "' is neither true nor false";
                case VALUES -> KeyDescription.list(value).isEmpty() ? "no value is given" : null;
                case REF -> FAMILY.matcher(value).matches() ? null : "'" + value + "' is not the name of a family";
                case WHEN -> isCondition(value) ? null
                        : "'" + value + "' is not a condition, written <sibling>=<value>[,<value>]";
                case DEFAULT, DOC, SOURCE, NAMES -> null;
            };
        }

        private static boolean isType(String type, boolean listAllowed) {
            if (listAllowed && type.startsWith(LIST)) {
                return isType(type.substring(LIST.length()), false);
            }
            return TYPES.contains(type) || JAVA_TYPE.matcher(type).matches();
        }

        private static boolean isCondition(String condition) {
            int equals = condition.indexOf('=');
            return equals > 0
                    && SEGMENT.matcher(condition.substring(0, equals).strip()).matches()
                    && !KeyDescription.list(condition.substring(equals + 1)).isEmpty();
        }

        private List<KeyDescription> descriptions() {
            List<KeyDescription> descriptions = new ArrayList<>(keys.size());
            keys.forEach((pattern, facts) -> descriptions.add(new KeyDescription(pattern, facts.kept)));
            return descriptions;
        }

        private List<KeyFamily> familyDescriptions() {
            List<KeyFamily> descriptions = new ArrayList<>(families.size());
            families.forEach((family, facts) -> descriptions.add(new KeyFamily(family,
                    KeyDescription.list(facts.value(Attribute.NAMES)),
                    Optional.ofNullable(facts.value(Attribute.DOC)))));
            return descriptions;
        }

        private void wrong(int line, String message) {
            problems.add(new Problem(origin, line, Problem.Kind.WRONG, message));
        }
    }
}
