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
package io.vidocq.runtime.maven.dev;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The {@code pom.xml} of a {@code vidocq:dev} session (Vidocq/vidocq#799, BUG-20261127-09). The session's module path
 * is resolved once, when it starts; {@code vidocq extension add} edits the pom and {@code module-info.java}, and the
 * recompile, a Maven of its own, compiles a module that {@code requires} what the session never resolved: the child
 * would die on a {@code FindException} at the next reload.
 *
 * <p>After each recompile that succeeded, and before the child is told to reload, {@link #check} compares the pom with
 * the one the session resolved. The same text: nothing to do. Another text: the dependencies are resolved again, and
 * when they differ, the session restarts on them; when they are the same, as for a property, the reload goes on and
 * the new text becomes the one the session runs on. Dependencies that do not resolve hold the reload: the application
 * keeps running on the previous ones, and every later recompile tries again.
 */
final class PomChange {

    /** How many dependency names the restart line gives before it counts the others. */
    private static final int NAMED = 3;

    /** What the session does after a recompile. */
    sealed interface Verdict permits Same, Restart, Hold {}

    /** The pom, or at least its dependencies, did not change: the reload goes on. */
    record Same() implements Verdict {}

    /** The dependencies changed: the session restarts on {@code project}, and logs {@code line}. */
    record Restart(MavenProject project, String line) implements Verdict {}

    /** The pom changed and its dependencies do not resolve: no reload, the application keeps running; logs {@code line}. */
    record Hold(String line) implements Verdict {}

    /** Resolves the dependencies of the pom as it is now. */
    @FunctionalInterface
    interface Resolver {

        /**
         * @return the project of the pom, its runtime dependencies resolved
         * @throws ResolutionFailedException when the pom cannot be read, or a dependency cannot be resolved
         */
        MavenProject resolve() throws ResolutionFailedException;
    }

    /** The pom or one of its dependencies cannot be resolved; the message says which. */
    static final class ResolutionFailedException extends Exception {

        private static final long serialVersionUID = 1L;

        ResolutionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path pom;
    private final MavenProject running;
    private final Resolver resolver;
    private byte[] resolvedText;

    /**
     * @param pom      the project's {@code pom.xml}
     * @param running  the project the session runs on: its dependencies are the ones resolved
     * @param resolver resolves the pom again
     */
    PomChange(Path pom, MavenProject running, Resolver resolver) {
        this.pom = pom;
        this.running = running;
        this.resolver = resolver;
        this.resolvedText = text(pom);
    }

    /**
     * The pom of {@code project}, read again with Maven's project builder ({@link PomResolver}).
     *
     * @param request the session's request, copied at each resolution
     */
    static PomChange of(MavenProject project, ProjectBuilder builder, ProjectBuildingRequest request) {
        return new PomChange(project.getFile().toPath(), project, new PomResolver(builder, request, project.getFile()));
    }

    /** What to do after a recompile that succeeded; see the class comment. */
    Verdict check() {
        byte[] now = text(pom);
        if (Arrays.equals(now, resolvedText)) {
            return new Same();
        }
        MavenProject resolved;
        try {
            resolved = resolver.resolve();
        } catch (ResolutionFailedException e) {
            return new Hold("pom.xml changed, but its dependencies do not resolve (" + e.getMessage() + "): the"
                    + " application keeps running on the previous ones, without this change. Fix pom.xml, or restart"
                    + " vidocq dev.");
        }
        if (keys(resolved.getArtifacts()).equals(keys(running.getArtifacts()))) {
            resolvedText = now;
            return new Same();
        }
        return new Restart(resolved, "pom.xml changed the dependencies (" + changes(running, resolved)
                + "): restarting the application on the new ones.");
    }

    /**
     * After a recompile that succeeded, whether the child may load its output: {@link #check}, then its line, to
     * {@code info} for a restart and to {@code warn} for a hold.
     *
     * @param restart gets the project to restart the session on, when the dependencies changed
     * @return {@code true} for the same dependencies; {@code false} for a restart or a hold
     */
    boolean reloads(Consumer<MavenProject> restart, Consumer<String> info, Consumer<String> warn) {
        return switch (check()) {
            case Same _ -> true;
            case Restart(MavenProject next, String line) -> {
                info.accept(line);
                restart.accept(next);
                yield false;
            }
            case Hold(String line) -> {
                warn.accept(line);
                yield false;
            }
        };
    }

    /**
     * What changed between the two projects, by the dependencies their poms declare: {@code added a, b; removed c}.
     * Only versions or the transitive dependencies changed: {@code the resolved dependencies changed}.
     */
    static String changes(MavenProject before, MavenProject after) {
        Set<String> was = declared(before.getDependencies());
        Set<String> is = declared(after.getDependencies());
        List<String> parts = new ArrayList<>();
        List<String> added = is.stream().filter(name -> !was.contains(name)).toList();
        List<String> removed = was.stream().filter(name -> !is.contains(name)).toList();
        if (!added.isEmpty()) {
            parts.add("added " + names(added));
        }
        if (!removed.isEmpty()) {
            parts.add("removed " + names(removed));
        }
        return parts.isEmpty() ? "the resolved dependencies changed" : String.join("; ", parts);
    }

    private static Set<String> declared(List<Dependency> dependencies) {
        return dependencies == null ? Set.of() : dependencies.stream().map(Dependency::getArtifactId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String names(List<String> names) {
        if (names.size() <= NAMED) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, NAMED)) + " and " + (names.size() - NAMED) + " more";
    }

    /** What tells two resolutions apart: each artifact's coordinates and its file. */
    static Set<String> keys(Collection<Artifact> artifacts) {
        return artifacts.stream().map(artifact -> artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                        + artifact.getType() + ":" + artifact.getClassifier() + ":" + artifact.getVersion() + ":"
                        + artifact.getScope() + "@" + artifact.getFile())
                .collect(Collectors.toSet());
    }

    /** The text of {@code pom}; empty when it does not exist or cannot be read, which the next look compares with. */
    private static byte[] text(Path pom) {
        try {
            return Files.readAllBytes(pom);
        } catch (IOException _) {
            return new byte[0];
        }
    }
}
