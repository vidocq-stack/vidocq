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
package io.vidocq.runtime.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which main method Vidocq runs, against what the Java SE launcher runs since Java 25 (JEP
 * 512): {@code main(String[])} or {@code main()}, static or an instance method, in the class,
 * a superclass or an implemented interface, and never required to be public. Selection follows
 * {@code jdk.internal.misc.MethodFinder}, so the cases below include the ones nobody guesses —
 * an interface default main, and a declared main that hides a usable inherited one.
 *
 * <p>Every expectation here was run through {@code java} itself on Temurin 25 before being
 * written down (vidocq#88).
 */
@DisplayName("vidocq#88 — the application main, chosen as the launcher chooses it")
class ApplicationMainTest {

    /** What a test main records, so the assertion sees which one ran and with which arguments. */
    static final List<String> CALLS = io.vidocq.runtime.core.mainshapes.MainCalls.CALLS;

    private static final String[] ARGS = {"--port", "9090"};

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    // --- accepted shapes -------------------------------------------------------------------

    static class PublicStaticWithArgs {
        public static void main(String[] args) {
            CALLS.add("public static main(String[]) " + String.join(" ", args));
        }
    }

    static class PackagePrivateStaticWithArgs {
        static void main(String[] args) {
            CALLS.add("static main(String[]) " + String.join(" ", args));
        }
    }

    static class StaticNoArgs {
        static void main() {
            CALLS.add("static main()");
        }
    }

    static class InstanceWithArgs {
        void main(String[] args) {
            CALLS.add("instance main(String[]) " + String.join(" ", args));
        }
    }

    static class InstanceNoArgs {
        void main() {
            CALLS.add("instance main()");
        }
    }

    @Test
    @DisplayName("a static main(String[]) runs and receives the arguments, public or not")
    void staticMainWithArguments() throws Exception {
        ApplicationMain.of(PublicStaticWithArgs.class).invoke(ARGS);
        ApplicationMain.of(PackagePrivateStaticWithArgs.class).invoke(ARGS);

        assertEquals(List.of("public static main(String[]) --port 9090", "static main(String[]) --port 9090"), CALLS);
    }

    @Test
    @DisplayName("a main() takes no arguments, and an instance main runs on a fresh instance")
    void theOtherThreeShapes() throws Exception {
        ApplicationMain.of(StaticNoArgs.class).invoke(ARGS);
        ApplicationMain.of(InstanceWithArgs.class).invoke(ARGS);
        ApplicationMain.of(InstanceNoArgs.class).invoke(ARGS);

        assertEquals(List.of("static main()", "instance main(String[]) --port 9090", "instance main()"), CALLS);
    }

    // --- selection order -------------------------------------------------------------------

    static class BothShapes {
        static void main() {
            CALLS.add("main()");
        }

        static void main(String[] args) {
            CALLS.add("main(String[])");
        }
    }

    static class Base {
        static void main(String[] args) {
            CALLS.add("inherited main(String[])");
        }
    }

    static class Derived extends Base {}

    static class DerivedWithOwnNoArgs extends Base {
        void main() {
            CALLS.add("own main()");
        }
    }

    @Test
    @DisplayName("main(String[]) wins over main(), including when only the superclass declares it")
    void argumentsWinOverNoArguments() throws Exception {
        ApplicationMain.of(BothShapes.class).invoke(ARGS);
        ApplicationMain.of(Derived.class).invoke(ARGS);
        ApplicationMain.of(DerivedWithOwnNoArgs.class).invoke(ARGS);

        assertEquals(List.of("main(String[])", "inherited main(String[])", "inherited main(String[])"), CALLS);
    }

    // --- interfaces ------------------------------------------------------------------------

    interface DefaultNoArgs {
        default void main() {
            CALLS.add("interface default main()");
        }
    }

    interface DefaultWithArgs {
        default void main(String[] args) {
            CALLS.add("interface default main(String[]) " + String.join(" ", args));
        }
    }

    interface StaticInInterface {
        static void main(String[] args) {
            CALLS.add("must not run");
        }
    }

    static class FromInterfaceNoArgs implements DefaultNoArgs {}

    static class FromInterfaceWithArgs implements DefaultWithArgs {}

    /** The interface's main(String[]) is selected over the class' own main(): arguments win. */
    static class OwnNoArgsUnderInterfaceWithArgs implements DefaultWithArgs {
        void main() {
            CALLS.add("must not run");
        }
    }

    static class FromStaticInterface implements StaticInInterface {}

    @Test
    @DisplayName("a default main on an implemented interface starts the application, a static one never does")
    void interfaceMains() throws Exception {
        ApplicationMain.of(FromInterfaceNoArgs.class).invoke(ARGS);
        ApplicationMain.of(FromInterfaceWithArgs.class).invoke(ARGS);
        ApplicationMain.of(OwnNoArgsUnderInterfaceWithArgs.class).invoke(ARGS);

        assertEquals(List.of("interface default main()",
                        "interface default main(String[]) --port 9090",
                        "interface default main(String[]) --port 9090"),
                CALLS,
                "an interface default main counts, and main(String[]) still wins over main()");

        var staticInInterface = assertThrows(IllegalStateException.class,
                () -> ApplicationMain.of(FromStaticInterface.class));
        assertTrue(staticInInterface.getMessage().contains("no main method"), staticInInterface.getMessage());
    }

    // --- a declared main hides an inherited one, usable or not -------------------------------

    /**
     * Its own main(String[]) is private: it still hides the base's, and main() takes over. The
     * base lives in another package on purpose — inside one package the compiler refuses the
     * visibility reduction, so this case cannot be written at all.
     */
    static class HidesWithPrivate extends io.vidocq.runtime.core.mainshapes.ArgsAndNoArgsBase {
        private static void main(String[] args) {
            CALLS.add("must not run");
        }
    }

    static class PrivateArgsWithNoArgs {
        private static void main(String[] args) {
            CALLS.add("must not run");
        }

        static void main() {
            CALLS.add("fallback main()");
        }
    }

    @Test
    @DisplayName("a declared main(String[]) that cannot be used hides the inherited one, and main() takes over")
    void aDeclaredMainHidesWhatItShadows() throws Exception {
        ApplicationMain.of(PrivateArgsWithNoArgs.class).invoke(ARGS);
        ApplicationMain.of(HidesWithPrivate.class).invoke(ARGS);

        assertEquals(List.of("fallback main()", "base main()"), CALLS,
                "the search stops at a declared match, usable or not, and the no-argument search resumes from the top");
    }

    // --- rejections ------------------------------------------------------------------------

    static class PrivateMain {
        private static void main(String[] args) {
            CALLS.add("must not run");
        }
    }

    static class NonVoidMain {
        static int main(String[] args) {
            CALLS.add("must not run");
            return 0;
        }
    }

    static class NoMainAtAll {}

    static class InstanceMainNoAccessibleConstructor {
        private InstanceMainNoAccessibleConstructor() {}

        void main() {
            CALLS.add("must not run");
        }
    }

    abstract static class AbstractInstanceMain {
        void main() {
            CALLS.add("must not run");
        }
    }

    @Test
    @DisplayName("a private or non-void main is named as the near miss, not reported as no main at all")
    void nearMissesAreNamed() {
        var isPrivate = assertThrows(IllegalStateException.class, () -> ApplicationMain.of(PrivateMain.class));
        assertTrue(isPrivate.getMessage().contains("main(String[]) is private"), isPrivate.getMessage());

        var nonVoid = assertThrows(IllegalStateException.class, () -> ApplicationMain.of(NonVoidMain.class));
        assertTrue(nonVoid.getMessage().contains("returns int instead of void"), nonVoid.getMessage());

        var none = assertThrows(IllegalStateException.class, () -> ApplicationMain.of(NoMainAtAll.class));
        assertTrue(none.getMessage().contains("it has no main method"), none.getMessage());
        assertTrue(none.getMessage().contains("Java 25 accepts main(String[]) or main(), static or not"),
                none.getMessage());

        assertEquals(List.of(), CALLS, "a rejected main is never invoked");
    }

    @Test
    @DisplayName("an instance main needs something to instantiate: a non-private constructor, and a concrete class")
    void instanceMainNeedsAnInstance() {
        var noConstructor = assertThrows(IllegalStateException.class,
                () -> ApplicationMain.of(InstanceMainNoAccessibleConstructor.class).invoke(ARGS));
        assertTrue(noConstructor.getMessage().contains("constructor")
                && noConstructor.getMessage().contains("is private"), noConstructor.getMessage());

        var isAbstract = assertThrows(IllegalStateException.class,
                () -> ApplicationMain.of(AbstractInstanceMain.class).invoke(ARGS));
        assertTrue(isAbstract.getMessage().contains("is abstract"), isAbstract.getMessage());

        assertEquals(List.of(), CALLS, "a rejected main is never invoked");
    }

    // --- what the caller sees --------------------------------------------------------------

    static class ThrowingMain {
        static void main(String[] args) {
            throw new IllegalArgumentException("boom");
        }
    }

    @Test
    @DisplayName("what the application throws comes back as the cause of the reflective call")
    void theApplicationsOwnFailureTravelsAsACause() {
        var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> ApplicationMain.of(ThrowingMain.class).invoke(ARGS));

        assertEquals("boom", thrown.getCause().getMessage(),
                "VidocqAppLayer unwraps it from here — that unwrapping is asserted in VidocqAppLayerMainTest");
    }
}
