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
package io.vidocq.runtime.tooling;

import io.vidocq.runtime.tooling.PomConverter.Element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Moves the pom of a Vidocq application to another Vidocq version (Vidocq/vidocq#510): {@code vidocq pom upgrade}
 * runs it.
 *
 * <p>Finds where the pom carries the version, as {@link PomConverter} reads the pom, on the text: the
 * {@code vidocq.version} property of a pom without a parent, the {@code <version>} of {@code vidocq-runtime-parent}
 * of a pom under it, and, in a pom written before 0.4.0, the literal version of every {@code io.vidocq.runtime}
 * artifact it declares (plugin, BOM, dependencies). Only the version moves: everything else keeps its place, and a
 * pom is not converted from one style to the other (that is {@code vidocq pom convert}).</p>
 *
 * <p>A pom whose versions disagree with each other, a literal left behind, is refused with the places and their
 * versions: aligning them silently would hide which one was meant. A SNAPSHOT target gets the Vidocq snapshot
 * repositories the scaffolder writes, unless the pom has them; a pom with repositories of its own is told to add
 * them instead.</p>
 *
 * <p>Pure: takes the pom text; no I/O. A pom it does not recognise is refused with an
 * {@link IllegalArgumentException} that says why.</p>
 */
public final class PomUpgrader {

    /** Where the pom carries a Vidocq version, as the reader names it, and what it says there. */
    public record Place(String name, String version, int start, int end) {}

    /** The Vidocq version of a pom and every place it is written. */
    public record Current(String version, List<Place> places) {}

    /** The upgraded pom, whether it changed, and what the user must do by hand. */
    public record Result(String pom, boolean changed, List<String> notes) {}

    private static final String SNAPSHOT = "-SNAPSHOT";
    private static final String SNAPSHOT_REPOSITORY_ID = "<id>vidocq-snapshots</id>";
    private static final String DEPENDENCY = "dependency";
    private static final String PLUGIN = "plugin";
    private static final String DEPENDENCIES = "dependencies";
    private static final String PLUGINS = "plugins";

    private PomUpgrader() {}

    /**
     * The Vidocq version the pom carries.
     *
     * @throws IllegalArgumentException when the pom carries none, or carries several that differ
     */
    public static Current current(String pom) {
        List<Place> places = places(pom);
        if (places.isEmpty()) {
            throw PomConverter.refused("no Vidocq version to be found: no " + PomConverter.VERSION_PROPERTY
                    + " property, no " + PomConverter.PARENT_ARTIFACT + ", no literal version on a "
                    + PomConverter.RUNTIME_GROUP + " artifact");
        }
        List<String> versions = places.stream().map(Place::version).distinct().toList();
        if (versions.size() > 1) {
            throw new IllegalArgumentException("the Vidocq versions of the pom disagree, nothing is aligned: "
                    + places.stream().map(p -> p.name() + " " + p.version()).collect(Collectors.joining(", "))
                    + ". Make them one version, or move them all to " + PomConverter.VERSION_PROPERTY + ".");
        }
        return new Current(versions.get(0), places);
    }

    /** The pom on {@code target}; unchanged when it already is. */
    public static Result upgrade(String pom, String target) {
        if (target == null || target.isBlank() || target.contains("<") || target.contains("$")) {
            throw new IllegalArgumentException("not a Vidocq version: '" + target + "'");
        }
        Current current = current(pom);
        if (current.version().equals(target)) {
            return new Result(pom, false, List.of());
        }
        String edited = pom;
        List<Place> descending = current.places().stream()
                .sorted(Comparator.comparingInt(Place::start).reversed()).toList();
        for (Place place : descending) {
            edited = edited.substring(0, place.start()) + target + edited.substring(place.end());
        }
        List<String> notes = new ArrayList<>();
        if (target.endsWith(SNAPSHOT) && !edited.contains(SNAPSHOT_REPOSITORY_ID)) {
            edited = withSnapshotRepositories(edited, notes);
        }
        return new Result(edited, true, List.copyOf(notes));
    }

    private static String withSnapshotRepositories(String pom, List<String> notes) {
        if (PomConverter.topLevel(pom, "repositories").isPresent()
                || PomConverter.topLevel(pom, "pluginRepositories").isPresent()) {
            notes.add("The pom has repositories of its own: add " + ProjectScaffolder.SNAPSHOT_REPOSITORY_URL
                    + " (<repositories> and <pluginRepositories>, snapshots enabled) to resolve a SNAPSHOT.");
            return pom;
        }
        Optional<Element> properties = PomConverter.topLevel(pom, "properties");
        int at;
        if (properties.isPresent()) {
            at = PomConverter.lineEnd(pom, properties.get().end());
        } else {
            Optional<Element> next = firstOf(pom, "dependencyManagement", DEPENDENCIES, "build");
            if (next.isEmpty()) {
                notes.add("Add " + ProjectScaffolder.SNAPSHOT_REPOSITORY_URL
                        + " as a repository and a plugin repository to resolve a SNAPSHOT.");
                return pom;
            }
            at = PomConverter.lineStart(pom, next.get().start());
        }
        return PomConverter.insert(pom, at, ProjectScaffolder.snapshotRepositories());
    }

    private static Optional<Element> firstOf(String pom, String... names) {
        for (String name : names) {
            Optional<Element> element = PomConverter.topLevel(pom, name);
            if (element.isPresent()) {
                return element;
            }
        }
        return Optional.empty();
    }

    // ---- where the pom says it ----

    private static List<Place> places(String pom) {
        List<Place> places = new ArrayList<>();
        PomConverter.topLevel(pom, "properties")
                .flatMap(p -> PomConverter.child(pom, p, PomConverter.VERSION_PROPERTY))
                .flatMap(e -> place(pom, PomConverter.VERSION_PROPERTY, e, true)).ifPresent(places::add);
        PomConverter.topLevel(pom, "parent").filter(p -> PomConverter.isVidocqParent(pom, p))
                .flatMap(p -> place(pom, PomConverter.PARENT_ARTIFACT, p, false)).ifPresent(places::add);
        PomConverter.topLevel(pom, "dependencyManagement")
                .ifPresent(m -> declared(pom, m, DEPENDENCIES, DEPENDENCY, places));
        PomConverter.topLevel(pom, DEPENDENCIES).ifPresent(d -> declaredIn(pom, d, DEPENDENCY, places));
        PomConverter.topLevel(pom, "build").ifPresent(build -> {
            declared(pom, build, PLUGINS, PLUGIN, places);
            PomConverter.child(pom, build, "pluginManagement")
                    .ifPresent(m -> declared(pom, m, PLUGINS, PLUGIN, places));
        });
        return places;
    }

    private static void declared(String pom, Element container, String listName, String itemName,
                                 List<Place> places) {
        PomConverter.child(pom, container, listName).ifPresent(list -> declaredIn(pom, list, itemName, places));
    }

    private static void declaredIn(String pom, Element list, String itemName, List<Place> places) {
        for (Element item : PomConverter.children(pom, list)) {
            if (itemName.equals(item.name()) && isRuntimeGroup(PomConverter.childText(pom, item, "groupId"))) {
                place(pom, PomConverter.childText(pom, item, "artifactId"), item, false).ifPresent(places::add);
            }
            if (PLUGIN.equals(itemName)) {
                processorPaths(pom, item, places);
            }
        }
    }

    /** The codegen bundles a pom before 0.4.0 wrote on the processor path of a plugin, a version each. */
    private static void processorPaths(String pom, Element plugin, List<Place> places) {
        PomConverter.child(pom, plugin, "configuration")
                .flatMap(c -> PomConverter.child(pom, c, "annotationProcessorPaths"))
                .ifPresent(paths -> {
                    for (Element path : PomConverter.children(pom, paths)) {
                        if ("path".equals(path.name())
                                && isRuntimeGroup(PomConverter.childText(pom, path, "groupId"))) {
                            place(pom, PomConverter.childText(pom, path, "artifactId"), path, false)
                                    .ifPresent(places::add);
                        }
                    }
                });
    }

    /** {@code io.vidocq.runtime} and its sub-groups, where the extensions are published. */
    private static boolean isRuntimeGroup(String groupId) {
        return groupId != null && (groupId.equals(PomConverter.RUNTIME_GROUP)
                || groupId.startsWith(PomConverter.RUNTIME_GROUP + "."));
    }

    /**
     * The place of the version: {@code holder} itself for the property, else its {@code <version>} child; empty
     * when there is none or it is not a literal (a {@code ${...}} reference is not a place of its own).
     */
    private static Optional<Place> place(String pom, String name, Element holder, boolean itself) {
        Optional<Element> version = itself ? Optional.of(holder) : PomConverter.child(pom, holder, "version");
        return version.filter(v -> PomConverter.literal(PomConverter.text(pom, v)))
                .map(v -> new Place(name, PomConverter.text(pom, v), v.contentStart(), v.contentEnd()));
    }
}
