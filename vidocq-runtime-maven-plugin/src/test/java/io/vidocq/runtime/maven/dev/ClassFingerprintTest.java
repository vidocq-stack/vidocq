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
import java.util.Optional;
import java.util.Set;

import static io.vidocq.runtime.maven.dev.HotSwapFixtures.fingerprint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFingerprintTest {

    @Test
    void theNamesComeFromTheClassFile(@TempDir Path dir) {
        var a = fingerprint(dir, "v1", "app.A", "package app; class A extends Thread implements Runnable {}");

        assertEquals("app.A", a.binaryName());
        assertEquals("A.java", a.sourceFile());
        assertEquals("java.lang.Thread", a.superclass());
        assertEquals(java.util.List.of("java.lang.Runnable"), a.interfaces());
    }

    @Test
    void aRenumberedConstantPoolIsNoChange(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.A", """
                package app;
                class A {
                    int foo() { return 1; }
                    String bar() { return "b"; }
                }
                """);
        var v2 = fingerprint(dir, "v2", "app.A", """
                package app;
                class A {
                    int foo() { String s = "new constant"; return s.length() + Integer.parseInt("42"); }
                    String bar() { return "b"; }
                }
                """);

        assertEquals(Optional.empty(), v2.firstShapeDifference(v1));
        assertEquals(Set.of("foo"), v2.methodsWithChangedCode(v1));
    }

    @Test
    void identicalSourcesGiveNoDifference(@TempDir Path dir) {
        String source = "package app; class A { int foo() { return 1; } }";

        var v1 = fingerprint(dir, "v1", "app.A", source);
        var v2 = fingerprint(dir, "v2", "app.A", source);

        assertEquals(Optional.empty(), v2.firstShapeDifference(v1));
        assertEquals(Set.of(), v2.methodsWithChangedCode(v1));
    }

    @Test
    void aMovedLineChangesTheCodeNotTheShape(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.A", """
                package app;
                class A {
                    String bar() { return "b"; }
                }
                """);
        var v2 = fingerprint(dir, "v2", "app.A", """
                package app;
                class A {
                    String bar() {

                        return "b";
                    }
                }
                """);

        assertEquals(Optional.empty(), v2.firstShapeDifference(v1));
        assertEquals(Set.of("bar"), v2.methodsWithChangedCode(v1));
    }

    @Test
    void aNewLambdaChangesTheShape(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.MainView", """
                package app;
                class MainView {
                    Runnable a = () -> System.out.println("a");
                }
                """);
        var v2 = fingerprint(dir, "v2", "app.MainView", """
                package app;
                class MainView {
                    Runnable a = () -> System.out.println("a");
                    Runnable b = () -> System.out.println("b");
                }
                """);

        assertEquals(Optional.of("gained a field b"), v2.firstShapeDifference(v1));
    }

    @Test
    void aSecondLambdaInABodyGainsAMethod(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.MainView", """
                package app;
                class MainView {
                    Runnable a() { return () -> System.out.println("a"); }
                }
                """);
        var v2 = fingerprint(dir, "v2", "app.MainView", """
                package app;
                class MainView {
                    Runnable a() { Runnable b = () -> {}; b.run(); return () -> System.out.println("a"); }
                }
                """);

        assertEquals(Optional.of("gained a method lambda$a$1"), v2.firstShapeDifference(v1));
    }

    @Test
    void aLambdaBodyChangesOnlyTheCode(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.MainView", """
                package app;
                class MainView {
                    Runnable a() { return () -> System.out.println("a"); }
                }
                """);
        var v2 = fingerprint(dir, "v2", "app.MainView", """
                package app;
                class MainView {
                    Runnable a() { return () -> System.out.println("changed"); }
                }
                """);

        assertEquals(Optional.empty(), v2.firstShapeDifference(v1));
        assertEquals(Set.of("lambda$a$0"), v2.methodsWithChangedCode(v1));
    }

    @Test
    void aLostField(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.Order", "package app; class Order { int total; int count; }");
        var v2 = fingerprint(dir, "v2", "app.Order", "package app; class Order { int count; }");

        assertEquals(Optional.of("lost a field total"), v2.firstShapeDifference(v1));
    }

    @Test
    void aChangedSignature(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.A", "package app; class A { void greet() {} }");
        var v2 = fingerprint(dir, "v2", "app.A", "package app; class A { void greet(String who) {} }");

        assertEquals(Optional.of("changed the signature of greet()"), v2.firstShapeDifference(v1));
    }

    @Test
    void aChangedModifier(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.A", "package app; class A { void greet() {} }");
        var v2 = fingerprint(dir, "v2", "app.A", "package app; class A { synchronized void greet() {} }");

        assertEquals(Optional.of("changed the modifiers of greet()"), v2.firstShapeDifference(v1));
    }

    @Test
    void aNewSuperclassOrInterface(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.A", "package app; class A {}");
        var v2 = fingerprint(dir, "v2", "app.A", "package app; class A extends Thread {}");
        var v3 = fingerprint(dir, "v3", "app.A", "package app; class A implements Runnable { public void run() {} }");

        assertEquals(Optional.of("changed its superclass"), v2.firstShapeDifference(v1));
        assertEquals(Optional.of("changed its interfaces"), v3.firstShapeDifference(v1));
    }

    @Test
    void anAnnotationOnTheClassAMethodAFieldOrAParameterChangesTheShape(@TempDir Path dir) {
        String q = """
                package app;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @interface Q { String value() default ""; }
                """;
        var v1 = ClassFingerprint.of(HotSwapFixtures.bytes(HotSwapFixtures.compile(dir, "v1", java.util.Map.of(
                "app/Q.java", q,
                "app/R.java", "package app; class R { int f; String hello(String who) { return who; } }")), "app.R"));

        assertEquals(Optional.of("changed an annotation on the class"), variant(dir, "c", q,
                "package app; @Q class R { int f; String hello(String who) { return who; } }")
                .firstShapeDifference(v1));
        assertEquals(Optional.of("changed an annotation on hello()"), variant(dir, "m", q,
                "package app; class R { int f; @Q String hello(String who) { return who; } }")
                .firstShapeDifference(v1));
        assertEquals(Optional.of("changed an annotation on field f"), variant(dir, "f", q,
                "package app; class R { @Q int f; String hello(String who) { return who; } }")
                .firstShapeDifference(v1));
        assertEquals(Optional.of("changed an annotation on hello()"), variant(dir, "p", q,
                "package app; class R { int f; String hello(@Q(\"x\") String who) { return who; } }")
                .firstShapeDifference(v1));
    }

    @Test
    void theLookupInnerClassEntryIsIgnored(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.A", """
                package app;
                class A {
                    String hello(String who) { return who; }
                }
                """);
        var v2 = fingerprint(dir, "v2", "app.A", """
                package app;
                class A {
                    String hello(String who) { return "Hello " + who; }
                }
                """);

        assertEquals(Optional.empty(), v2.firstShapeDifference(v1));
        assertEquals(Set.of("hello"), v2.methodsWithChangedCode(v1));
    }

    @Test
    void aConstructorBodyIsCodeOfInit(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.A", "package app; class A { String label = \"Say hello\"; }");
        var v2 = fingerprint(dir, "v2", "app.A", "package app; class A { String label = \"Say hi\"; }");

        assertEquals(Optional.empty(), v2.firstShapeDifference(v1));
        assertEquals(Set.of("<init>"), v2.methodsWithChangedCode(v1));
    }

    private static final String ORDER = "package app; class Order implements java.io.Serializable { %s }";
    private static final String VIEW = """
            package app;
            class MainView {
                java.util.List<java.util.function.Supplier<String>> actions = new java.util.ArrayList<>();
                MainView() { %s }
            }
            """;

    @Test
    void aGainedInstanceFieldIsAnInstanceShapeDifference(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.Order", ORDER.formatted("int count;"));
        var v2 = fingerprint(dir, "v2", "app.Order", ORDER.formatted("int count; int total;"));

        assertEquals(Optional.of("gained a field total"), v2.instanceShapeDifference(v1));
    }

    @Test
    void aGainedStaticFieldIsNot(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.Order", ORDER.formatted("int count;"));
        var v2 = fingerprint(dir, "v2", "app.Order", ORDER.formatted("int count; static int total;"));

        assertEquals(Optional.empty(), v2.instanceShapeDifference(v1));
    }

    @Test
    void aLostTransientFieldIs(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.Order", ORDER.formatted("int count; transient String cache;"));
        var v2 = fingerprint(dir, "v2", "app.Order", ORDER.formatted("int count;"));

        assertEquals(Optional.of("lost a field cache"), v2.instanceShapeDifference(v1));
    }

    @Test
    void aRetypedFieldIs(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.Order", ORDER.formatted("int total;"));
        var v2 = fingerprint(dir, "v2", "app.Order", ORDER.formatted("long total;"));

        assertEquals(Optional.of("changed the type of field total"), v2.instanceShapeDifference(v1));
    }

    @Test
    void aNewSuperclassIs(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.Order", ORDER.formatted("int total;"));
        var v2 = fingerprint(dir, "v2", "app.Order",
                ORDER.replace("class Order", "class Order extends java.util.Date").formatted("int total;"));

        assertEquals(Optional.of("changed its superclass"), v2.instanceShapeDifference(v1));
    }

    @Test
    void aLambdaInsertedBeforeAnotherMovesTheLambdas(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.MainView", VIEW.formatted(
                "actions.add(() -> \"save\"); actions.add(() -> \"cancel\");"));
        var v2 = fingerprint(dir, "v2", "app.MainView", VIEW.formatted(
                "actions.add(() -> \"reset\"); actions.add(() -> \"save\"); actions.add(() -> \"cancel\");"));

        assertTrue(v2.lambdasMoved(v1));
    }

    @Test
    void aLambdaAppendedDoesNotMoveThem(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.MainView", VIEW.formatted(
                "actions.add(() -> \"save\"); actions.add(() -> \"cancel\");"));
        var v2 = fingerprint(dir, "v2", "app.MainView", VIEW.formatted(
                "actions.add(() -> \"save\"); actions.add(() -> \"cancel\"); actions.add(() -> \"reset\");"));

        assertFalse(v2.lambdasMoved(v1));
    }

    @Test
    void aLambdaBodyEditedInPlaceDoesNotMoveThem(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.MainView", VIEW.formatted(
                "actions.add(() -> \"save\"); actions.add(() -> \"cancel\");"));
        var v2 = fingerprint(dir, "v2", "app.MainView", VIEW.formatted(
                "actions.add(() -> \"store\"); actions.add(() -> \"cancel\");"));

        assertFalse(v2.lambdasMoved(v1));
    }

    @Test
    void theLastLambdaRemovedDoesNotMoveThem(@TempDir Path dir) {
        var v1 = fingerprint(dir, "v1", "app.MainView", VIEW.formatted(
                "actions.add(() -> \"save\"); actions.add(() -> \"cancel\");"));
        var v2 = fingerprint(dir, "v2", "app.MainView", VIEW.formatted("actions.add(() -> \"save\");"));

        assertFalse(v2.lambdasMoved(v1));
    }

    private static ClassFingerprint variant(Path dir, String name, String q, String source) {
        return ClassFingerprint.of(HotSwapFixtures.bytes(HotSwapFixtures.compile(dir, name,
                java.util.Map.of("app/Q.java", q, "app/R.java", source)), "app.R"));
    }
}
