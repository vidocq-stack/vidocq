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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The keys several jars describe, merged: what a tool, or the runtime, knows of the configuration keys of an
 * application.
 *
 * <p>In a jar the file its author wrote is read first, then the generated one; jars come in the order given.
 * For one key, {@code values} add up, as {@code names} do for a family; for every other attribute the first
 * fact met is kept, so that what an author writes wins over what a build generates.
 */
public final class KeyCatalog {

    /** The file the author of a jar writes, for the keys code reads by program. */
    public static final String WRITTEN = "META-INF/vidocq/config-user-keys.properties";

    /** The file a build generates, for the keys annotations declare. */
    public static final String GENERATED = "META-INF/vidocq/config-keys.properties";

    private static final String LIST = "list:";

    private final List<KeyDescription> keys;
    private final Map<String, KeyFamily> families;
    private final List<Problem> problems;

    private KeyCatalog(List<KeyDescription> keys, Map<String, KeyFamily> families, List<Problem> problems) {
        this.keys = List.copyOf(keys);
        this.families = families;
        this.problems = List.copyOf(problems);
    }

    /**
     * What one jar says of its keys.
     *
     * @param written   the file its author wrote, or {@link KeyDescriptions#none()}
     * @param generated the generated file, or {@link KeyDescriptions#none()}
     */
    public record Jar(KeyDescriptions written, KeyDescriptions generated) {

        public Jar {
            Objects.requireNonNull(written, "written: KeyDescriptions.none() for a jar that has no such file");
            Objects.requireNonNull(generated, "generated: KeyDescriptions.none() for a jar that has no such file");
        }
    }

    /**
     * Merges what jars say of their keys.
     *
     * @param jars the jars, in the order their facts are to be kept
     * @return the catalogue of their keys
     */
    public static KeyCatalog of(List<Jar> jars) {
        return new Merge().of(jars);
    }

    /** The keys, in the order of the text of their patterns. */
    public List<KeyDescription> keys() {
        return keys;
    }

    /**
     * What is known of a key an application writes.
     *
     * @param key the key, as written
     * @return the description that matches it best: a literal segment wins over a name, a pattern that
     *         describes the key over one that covers it with a tail; of two that match as well, the first in the
     *         order of {@link #keys()}. Empty when no pattern matches
     */
    public Optional<KeyDescription> describe(String key) {
        KeyDescription best = null;
        KeyPattern.Match bestMatch = null;
        for (KeyDescription description : keys) {
            Optional<KeyPattern.Match> match = description.key().match(key);
            if (match.isPresent() && (bestMatch == null || match.get().compareTo(bestMatch) < 0)) {
                best = description;
                bestMatch = match.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A family of names.
     *
     * @param name the family
     * @return the family, when a file says something of it or a pattern names it
     */
    public Optional<KeyFamily> family(String name) {
        return Optional.ofNullable(families.get(name));
    }

    /** What is wrong: in each file, in the order given, then between the files. */
    public List<Problem> problems() {
        return problems;
    }

    /** The facts kept for one key or one family. */
    private static final class Facts {

        private final Map<Attribute, Fact> kept = new EnumMap<>(Attribute.class);
        /** The items of {@code values}, or of {@code names} for a family: they add up. */
        private final Set<String> items = new LinkedHashSet<>();

        /** Keeps a fact that is the first of its attribute; answers the one kept before it otherwise. */
        Fact keep(Attribute attribute, Fact fact) {
            if (attribute == Attribute.VALUES) {
                items.addAll(KeyDescription.list(fact.value()));
                kept.putIfAbsent(attribute, fact);
                return null;
            }
            return kept.putIfAbsent(attribute, fact);
        }

        boolean allows(String value) {
            return items.isEmpty() || items.stream().anyMatch(value::equalsIgnoreCase);
        }
    }

    /** The merge of the files of several jars. */
    private static final class Merge {

        private final Map<KeyPattern, Facts> keys = new LinkedHashMap<>();
        private final Map<String, Facts> families = new LinkedHashMap<>();
        private final List<Problem> problems = new ArrayList<>();
        private final List<Problem> between = new ArrayList<>();

        KeyCatalog of(List<Jar> jars) {
            for (Jar jar : jars) {
                problems.addAll(jar.written().problems());
                problems.addAll(jar.generated().problems());
                jar(jar);
            }
            for (KeyPattern pattern : keys.keySet()) {
                pattern.families().forEach(family -> families.computeIfAbsent(family, f -> new Facts()));
            }
            Map<String, KeyDescription> sorted = new TreeMap<>();
            keys.forEach((pattern, facts) -> sorted.put(pattern.text(), description(pattern, facts)));
            sorted.values().forEach(this::check);
            problems.addAll(between);
            Map<String, KeyFamily> known = new LinkedHashMap<>();
            families.forEach((name, facts) -> known.put(name, new KeyFamily(name, List.copyOf(facts.items),
                    Optional.ofNullable(facts.kept.get(Attribute.DOC)).map(Fact::value))));
            return new KeyCatalog(new ArrayList<>(sorted.values()), known, problems);
        }

        /** One jar: its two files first, the written one winning, then what they say against the earlier jars. */
        private void jar(Jar jar) {
            Map<KeyPattern, Facts> own = new LinkedHashMap<>();
            for (KeyDescriptions file : List.of(jar.written(), jar.generated())) {
                for (KeyDescription key : file.keys()) {
                    Facts facts = own.computeIfAbsent(key.key(), p -> new Facts());
                    key.facts().forEach((attribute, fact) -> {
                        Fact written = facts.keep(attribute, fact);
                        boolean contradicted = written != null && attribute == Attribute.DEFAULT
                                && !written.value().equals(fact.value());
                        if (contradicted) {
                            wrong(written, key.key().text() + "|default is '" + written.value() + "' here and '"
                                    + fact.value() + "' in " + fact.origin() + ": this one is kept");
                        }
                    });
                }
                for (KeyFamily family : file.families()) {
                    Facts facts = families.computeIfAbsent(family.name(), f -> new Facts());
                    facts.items.addAll(family.names());
                    family.doc().ifPresent(
                            doc -> facts.kept.putIfAbsent(Attribute.DOC, new Fact(doc, file.origin(), 0)));
                }
            }
            own.forEach(this::merge);
        }

        /** What one jar says of a key, against what the jars before it said. */
        private void merge(KeyPattern pattern, Facts jar) {
            Facts facts = keys.computeIfAbsent(pattern, p -> new Facts());
            jar.kept.forEach((attribute, fact) -> {
                if (attribute == Attribute.VALUES) {
                    facts.kept.putIfAbsent(attribute, fact);
                    return;
                }
                Fact earlier = facts.keep(attribute, fact);
                boolean mustAgree = attribute == Attribute.TYPE || attribute == Attribute.DEFAULT;
                if (earlier != null && mustAgree && !earlier.value().equals(fact.value())) {
                    wrong(fact, pattern.text() + "|" + attribute.text() + " is '" + fact.value() + "' here and '"
                            + earlier.value() + "' in " + earlier.origin() + ", which is kept");
                }
            });
            facts.items.addAll(jar.items);
        }

        private static KeyDescription description(KeyPattern pattern, Facts facts) {
            Map<Attribute, Fact> kept = new EnumMap<>(facts.kept);
            Fact first = kept.get(Attribute.VALUES);
            if (first != null) {
                kept.put(Attribute.VALUES, new Fact(String.join(",", facts.items), first.origin(), first.line()));
            }
            return new KeyDescription(pattern, kept);
        }

        /** What a key says that the other keys and the families must bear out. */
        private void check(KeyDescription key) {
            Facts facts = keys.get(key.key());
            String name = key.key().text();
            key.fact(Attribute.DEFAULT).ifPresent(fact -> {
                // An empty default is no value; the default of a list is its items.
                List<String> items = key.type().startsWith(LIST) || fact.value().isEmpty()
                        ? KeyDescription.list(fact.value())
                        : List.of(fact.value());
                for (String item : items) {
                    if (!facts.allows(item)) {
                        wrong(fact, name + "|default is '" + fact.value() + "', and '" + item
                                + "' is not one of its values: " + key.values());
                    }
                }
            });
            key.fact(Attribute.REF).filter(fact -> !families.containsKey(fact.value())).ifPresent(fact -> wrong(fact,
                    name + "|ref names the family '" + fact.value()
                            + "', which no file describes and no key names"));
            key.when().ifPresent(condition -> condition(key, condition, facts.kept.get(Attribute.WHEN)));
        }

        private void condition(KeyDescription key, KeyDescription.Condition condition, Fact fact) {
            KeyPattern sibling;
            try {
                sibling = key.key().sibling(condition.sibling());
            } catch (IllegalArgumentException e) {
                wrong(fact, key.key().text() + "|when: " + e.getMessage());
                return;
            }
            Facts facts = keys.get(sibling);
            if (facts == null) {
                wrong(fact, key.key().text() + "|when is on " + sibling.text() + ", which no file describes");
                return;
            }
            for (String value : condition.values()) {
                if (!facts.allows(value)) {
                    wrong(fact, key.key().text() + "|when is on the value '" + value + "' of " + sibling.text()
                            + ", which is not one of its values: " + List.copyOf(facts.items));
                }
            }
        }

        /** A problem between files, at the fact that is wrong. */
        private void wrong(Fact at, String message) {
            between.add(new Problem(at.origin(), at.line(), Problem.Kind.WRONG, message));
        }
    }
}
