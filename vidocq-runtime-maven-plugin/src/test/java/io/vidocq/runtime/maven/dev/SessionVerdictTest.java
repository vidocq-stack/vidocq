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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static io.vidocq.runtime.maven.dev.HotSwapProject.build;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionVerdictTest {

    private static final String ORDER = "package app; class Order implements java.io.Serializable { %s }";
    /** A view whose listeners, an {@code Action}, are serializable, as Vaadin's are. */
    private static final String VIEW = """
            package app;
            class MainView implements java.io.Serializable {
                interface Action extends java.util.function.Supplier<String>, java.io.Serializable {}
                java.util.List<Action> actions = new java.util.ArrayList<>();
                java.util.List<java.util.function.Supplier<String>> plain = new java.util.ArrayList<>();
                MainView() { %s }
            }
            """;
    private static final String SAVE_CANCEL = "actions.add(() -> \"save\"); actions.add(() -> \"cancel\");";
    private static final String ANONYMOUS = "actions.add(new Action() { public String get() { return \"%s\"; } });";
    private static final String MOVED = "MainView's %s moved, and a restored listener could run another one's code";

    @TempDir
    Path dir;

    @Test
    void keepSessionsFalseDrops() {
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/Order.java", ORDER.formatted("int count;")));
        var next = project.apply(build().source("app/Order.java", ORDER.formatted("int count; int total;")));

        assertEquals(Optional.of("vidocq.dev.keepSessions is false"), SessionVerdict.dropReason(base, next,
                SupertypeIndex.of(next, List.of(), _ -> { }), false));
    }

    @Test
    void aFieldAddedDropsWithOrWithoutADeclaredSerialVersionUid() {
        String declared = "private static final long serialVersionUID = 1L; ";
        var reason = Optional.of("Order gained a field total, so a restored instance would not match the new class");

        assertEquals(reason, fieldAdded("without", ""));
        assertEquals(reason, fieldAdded("with", declared));
    }

    @Test
    void aLambdaInsertedFirstDrops() {
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/MainView.java", VIEW.formatted(SAVE_CANCEL)));
        var next = project.apply(build().source("app/MainView.java",
                VIEW.formatted("actions.add(() -> \"reset\"); " + SAVE_CANCEL)));

        assertEquals(Optional.of(MOVED.formatted("lambdas")), dropReason(base, next));
    }

    @Test
    void lambdasNoSessionCanHoldKeepWhenOneIsInsertedFirst() {
        String plain = "plain.add(() -> \"save\"); plain.add(() -> \"cancel\");";
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/MainView.java", VIEW.formatted(plain)));
        var next = project.apply(build().source("app/MainView.java",
                VIEW.formatted("plain.add(() -> \"reset\"); " + plain)));

        assertEquals(Optional.empty(), dropReason(base, next));
    }

    @Test
    void anAnonymousClassInsertedFirstDrops() {
        String saveCancel = ANONYMOUS.formatted("save") + ANONYMOUS.formatted("cancel");
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/MainView.java", VIEW.formatted(saveCancel)));
        var next = project.apply(build().source("app/MainView.java",
                VIEW.formatted(ANONYMOUS.formatted("reset") + saveCancel)));

        assertEquals(Optional.of(MOVED.formatted("anonymous classes")), dropReason(base, next));
    }

    @Test
    void anAnonymousClassAppendedKeeps() {
        String saveCancel = ANONYMOUS.formatted("save") + ANONYMOUS.formatted("cancel");
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/MainView.java", VIEW.formatted(saveCancel)));
        var next = project.apply(build().source("app/MainView.java",
                VIEW.formatted(saveCancel + ANONYMOUS.formatted("reset"))));

        assertEquals(Optional.empty(), dropReason(base, next));
    }

    @Test
    void aFieldAddedToAClassNoSessionCanHoldKeeps() {
        String resource = "package app; class Resource { %s }";
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/Resource.java", resource.formatted("int count;")));
        var next = project.apply(build().source("app/Resource.java", resource.formatted("int count; int total;")));

        assertEquals(Optional.empty(), dropReason(base, next));
    }

    @Test
    void aLambdaAppendedKeeps() {
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/MainView.java", VIEW.formatted(SAVE_CANCEL)));
        var next = project.apply(build().source("app/MainView.java",
                VIEW.formatted(SAVE_CANCEL + " actions.add(() -> \"reset\");")));

        assertEquals(Optional.empty(), dropReason(base, next));
    }

    @Test
    void aNewClassKeeps() {
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/Order.java", ORDER.formatted("int count;")));
        var next = project.apply(build().source("app/Order.java", ORDER.formatted("int count;"))
                .source("app/Line.java", "package app; class Line implements java.io.Serializable { int total; }"));

        assertEquals(Optional.empty(), dropReason(base, next));
    }

    private Optional<String> fieldAdded(String name, String serialVersionUid) {
        var project = new HotSwapProject(dir.resolve(name), List.of());
        var base = project.apply(build().source("app/Order.java", ORDER.formatted(serialVersionUid + "int count;")));
        var next = project.apply(build().source("app/Order.java",
                ORDER.formatted(serialVersionUid + "int count; int total;")));
        return dropReason(base, next);
    }

    private static Optional<String> dropReason(ClassesSnapshot base, ClassesSnapshot next) {
        return SessionVerdict.dropReason(base, next, SupertypeIndex.of(next, List.of(), _ -> { }), true);
    }
}
