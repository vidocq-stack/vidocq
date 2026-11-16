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
    private static final String VIEW = """
            package app;
            class MainView {
                java.util.List<java.util.function.Supplier<String>> actions = new java.util.ArrayList<>();
                MainView() { %s }
            }
            """;
    private static final String SAVE_CANCEL = "actions.add(() -> \"save\"); actions.add(() -> \"cancel\");";

    @TempDir
    Path dir;

    @Test
    void keepSessionsFalseDrops() {
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/Order.java", ORDER.formatted("int count;")));
        var next = project.apply(build().source("app/Order.java", ORDER.formatted("int count; int total;")));

        assertEquals(Optional.of("vidocq.dev.keepSessions is false"), SessionVerdict.dropReason(base, next, false));
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

        assertEquals(Optional.of("MainView's lambdas moved, and a restored listener could run another one's code"),
                SessionVerdict.dropReason(base, next, true));
    }

    @Test
    void aLambdaAppendedKeeps() {
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/MainView.java", VIEW.formatted(SAVE_CANCEL)));
        var next = project.apply(build().source("app/MainView.java",
                VIEW.formatted(SAVE_CANCEL + " actions.add(() -> \"reset\");")));

        assertEquals(Optional.empty(), SessionVerdict.dropReason(base, next, true));
    }

    @Test
    void aNewClassKeeps() {
        var project = new HotSwapProject(dir, List.of());
        var base = project.apply(build().source("app/Order.java", ORDER.formatted("int count;")));
        var next = project.apply(build().source("app/Order.java", ORDER.formatted("int count;"))
                .source("app/Line.java", "package app; class Line implements java.io.Serializable { int total; }"));

        assertEquals(Optional.empty(), SessionVerdict.dropReason(base, next, true));
    }

    private Optional<String> fieldAdded(String name, String serialVersionUid) {
        var project = new HotSwapProject(dir.resolve(name), List.of());
        var base = project.apply(build().source("app/Order.java", ORDER.formatted(serialVersionUid + "int count;")));
        var next = project.apply(build().source("app/Order.java",
                ORDER.formatted(serialVersionUid + "int count; int total;")));
        return SessionVerdict.dropReason(base, next, true);
    }
}
