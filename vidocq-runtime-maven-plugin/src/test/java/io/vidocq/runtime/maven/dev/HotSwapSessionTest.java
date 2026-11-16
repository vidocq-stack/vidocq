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

import io.vidocq.runtime.core.ReloadRequest;
import io.vidocq.runtime.maven.depgen.DepgenFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.vidocq.runtime.maven.dev.HotSwapProject.build;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotSwapSessionTest {

    private static final String A = "package app; class A { int a() { return %s; } int b() { return %s; } }";
    private static final String LISTENERS = "package app; class A implements java.io.Serializable { "
            + "java.util.List<Runnable> listeners = "
            + "new java.util.ArrayList<>(); %s A() { %s } }";
    private static final Optional<String> KEEP = Optional.empty();
    /** A view whose listeners are serializable lambdas, numbered by javac in source order. */
    private static final String VIEW = "package app; class V implements java.io.Serializable { "
            + "interface L extends Runnable, java.io.Serializable {} "
            + "java.util.List<L> listeners = new java.util.ArrayList<>(); V() { %s } }";
    private static final String SAVE = "listeners.add(() -> System.out.println(\"save\")); ";
    private static final String CANCEL = "listeners.add(() -> System.out.println(\"cancel\")); ";
    private static final Optional<String> MOVED =
            Optional.of("V's lambdas moved, and a restored listener could run another one's code");

    @TempDir
    Path dir;
    private final List<String> info = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @Test
    void anApplicationWithNoDeclarationHasNoSession() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1")));

        var session = HotSwapSession.open(List.of(project.classes), List.of(project.src), List.of(plainJar()), null,
                true, info::add, warnings::add);

        assertTrue(session.isEmpty());
        assertEquals(List.of("Hot swap off: no dependency declares its start-time code (" + StartTimeDeclarations.FILE
                + "), every change swaps the application layer"), info);
    }

    @Test
    void theStartupLineCountsTheDeclaringJars() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1")));

        HotSwapSession.open(List.of(project.classes), List.of(project.src),
                List.of(declaringJar("one"), plainJar(), declaringJar("two")), null, true, info::add,
                warnings::add);

        assertEquals(List.of("Hot swap: start-time declarations from 2 jars"), info);
    }

    @Test
    void theApplicationsOwnFileCountsInTheStartupLine() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1"))
                .resource(StartTimeDeclarations.FILE, "long-lived-annotations = app.Keep\n"));

        HotSwapSession.open(List.of(project.classes), List.of(project.src), List.of(declaringJar("one")), null,
                true, info::add, warnings::add);

        assertEquals(List.of("Hot swap: start-time declarations from 1 jar and the application"), info);
    }

    @Test
    void anUnchangedOutputIsNothing() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1")));
        var session = open(project);

        assertEquals(Optional.empty(), session.next());
    }

    @Test
    void aFailedRecompileKeepsTheBase() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1")));
        var session = open(project);
        // a recompile that failed halfway: the mojo does not ask the session
        project.apply(build().source("app/A.java", A.formatted("2", "1")));

        project.apply(build().source("app/A.java", A.formatted("1", "2")));

        assertRedefine(session.next(), "b");
    }

    @Test
    void theBaseMovesOnlyOnAcknowledgement() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1")));
        var session = open(project);

        project.apply(build().source("app/A.java", A.formatted("2", "1")));
        assertRedefine(session.next(), "a");
        project.apply(build().source("app/A.java", A.formatted("2", "2")));
        assertRedefine(session.next(), "a", "b");
        session.acknowledged();
        project.apply(build().source("app/A.java", A.formatted("3", "2")));

        assertRedefine(session.next(), "a");
    }

    @Test
    void aLostRequestMakesTheNextOneASwap() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1")));
        var session = open(project);
        project.apply(build().source("app/A.java", A.formatted("2", "1")));
        session.next();
        session.lost();

        project.apply(build().source("app/A.java", A.formatted("3", "1")));
        assertEquals(Optional.of(new ReloadRequest.Swap("the previous reload was not confirmed", KEEP)), session.next());
        session.acknowledged();
        project.apply(build().source("app/A.java", A.formatted("3", "2")));

        assertRedefine(session.next(), "b");
    }

    @Test
    void theApplicationsOwnFileIsReadAgainAfterEachRecompile() {
        String keep = "package app; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                + "@interface Keep {}";
        String kept = "package app; @Keep class Kept { Kept() { System.out.println(\"%s\"); } }";
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/Keep.java", keep).source("app/Kept.java", kept.formatted("a")));
        var session = open(project);

        project.apply(build().source("app/Keep.java", keep).source("app/Kept.java", kept.formatted("a"))
                .resource(StartTimeDeclarations.FILE, "long-lived-annotations = app.Keep\n"));
        assertEquals(Optional.of(new ReloadRequest.Swap(StartTimeDeclarations.FILE + " was added", KEEP)), session.next());
        session.acknowledged();
        project.apply(build().source("app/Keep.java", keep).source("app/Kept.java", kept.formatted("b"))
                .resource(StartTimeDeclarations.FILE, "long-lived-annotations = app.Keep\n"));

        assertEquals(Optional.of(new ReloadRequest.Swap("Kept's constructor ran at start", KEEP)), session.next());
    }

    @Test
    void withoutRedefinitionABodyChangeSwapsSayingSo() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1"))
                .source("app/B.java", A.replace("class A", "class B").formatted("1", "1")));
        var session = HotSwapSession.open(List.of(project.classes), List.of(project.src),
                List.of(declaringJar("one")), "class redefinition is off: vidocq.dev.hotSwap=false", true,
                info::add, warnings::add).orElseThrow();

        project.apply(build().source("app/A.java", A.formatted("2", "1"))
                .source("app/B.java", A.replace("class A", "class B").formatted("2", "1")));

        assertEquals(Optional.of(new ReloadRequest.Swap(
                "2 classes changed in method bodies only; class redefinition is off: vidocq.dev.hotSwap=false", KEEP)),
                session.next());
    }

    @Test
    void aWarningIsNotRepeatedAtEachRecompile() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1"))
                .resource(StartTimeDeclarations.FILE, "no-such-key = x\n"));
        var session = open(project);
        int atStart = warnings.size();

        project.apply(build().source("app/A.java", A.formatted("2", "1"))
                .resource(StartTimeDeclarations.FILE, "no-such-key = x\n"));
        session.next();
        session.acknowledged();
        project.apply(build().source("app/A.java", A.formatted("3", "1"))
                .resource(StartTimeDeclarations.FILE, "no-such-key = x\n"));
        session.next();

        assertEquals(1, atStart, warnings::toString);
        assertEquals(1, warnings.size(), warnings::toString);
    }

    @Test
    void aSwapCarriesTheVerdict() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", LISTENERS.formatted("", "listeners.add(() -> {});")));
        var session = open(project);

        project.apply(build().source("app/A.java",
                LISTENERS.formatted("", "listeners.add(() -> {}); listeners.add(() -> {});")));

        var swap = assertInstanceOf(ReloadRequest.Swap.class, session.next().orElseThrow());
        assertEquals(KEEP, swap.dropSessions());
    }

    @Test
    void aRedefinitionCarriesNone() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", A.formatted("1", "1")));
        var session = open(project);

        project.apply(build().source("app/A.java", A.formatted("2", "1")));

        assertRedefine(session.next(), "a");
    }

    @Test
    void anUnconfirmedRequestStillChecksAgainstTheAcknowledgedOutput() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/A.java", LISTENERS.formatted("", "")));
        var session = open(project);
        project.apply(build().source("app/A.java", LISTENERS.formatted("", "System.out.println();")));
        session.next();
        session.lost();

        project.apply(build().source("app/A.java", LISTENERS.formatted("int total;", "System.out.println();")));

        assertEquals(Optional.of(new ReloadRequest.Swap("the previous reload was not confirmed",
                Optional.of("A gained a field total, so a restored instance would not match the new class"))),
                session.next());
    }

    @Test
    void anUnconfirmedRequestIsAlsoCheckedAgainstTheOutputTheChildMayRun() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/V.java", VIEW.formatted(SAVE + CANCEL)));
        var session = open(project);
        project.apply(build().source("app/V.java", VIEW.formatted(CANCEL)));
        session.next();
        session.lost();

        project.apply(build().source("app/V.java", VIEW.formatted(SAVE + CANCEL)));

        assertEquals(Optional.of(new ReloadRequest.Swap("the previous reload was not confirmed", MOVED)),
                session.next());
    }

    @Test
    void afterARespawnTheNextSwapIsCheckedAgainstTheOutputItLoaded() {
        var project = new HotSwapProject(dir, List.of());
        project.apply(build().source("app/V.java", VIEW.formatted(SAVE + CANCEL)));
        var session = open(project);
        project.apply(build().source("app/V.java", VIEW.formatted(CANCEL)));
        session.respawned();

        project.apply(build().source("app/V.java", VIEW.formatted(SAVE + CANCEL)));

        assertEquals(Optional.of(new ReloadRequest.Swap("the previous reload was not confirmed", MOVED)),
                session.next());
    }

    private HotSwapSession open(HotSwapProject project) {
        return HotSwapSession.open(List.of(project.classes), List.of(project.src), List.of(declaringJar("one")), null,
                true, info::add, warnings::add).orElseThrow();
    }

    private Path declaringJar(String name) {
        return jar(name, Map.of("lib/" + name + "/Marker.java", "package lib." + name + "; public @interface Marker {}"),
                Map.of(StartTimeDeclarations.FILE, "long-lived-annotations = lib." + name + ".Marker\n"));
    }

    private Path plainJar() {
        return jar("plain", Map.of("lib/plain/P.java", "package lib.plain; public class P {}"), Map.of());
    }

    private Path jar(String name, Map<String, String> sources, Map<String, String> entries) {
        try {
            return DepgenFixtures.jar(dir, name, sources, List.of(), entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertRedefine(Optional<ReloadRequest> result, String... methods) {
        var redefine = assertInstanceOf(ReloadRequest.Redefine.class, result.orElseThrow(), result::toString);
        assertEquals(List.of("app.A"), List.copyOf(redefine.methodsByClass().keySet()));
        assertEquals(List.of(methods), List.copyOf(redefine.methodsByClass().get("app.A")));
    }
}
