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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VidocqDevReloadLoopTest {

    @Test
    void theReadyFileIsTheReloadFileWithReady(@TempDir Path dir) {
        assertEquals(dir.resolve(".vidocq-dev-reload.ready"),
                VidocqDevReloadLoop.readyFile(dir.resolve(".vidocq-dev-reload")));
    }

    @Test
    void acknowledgingWritesTheStampAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        Path reload = dir.resolve(".vidocq-dev-reload");

        VidocqDevReloadLoop.acknowledge(reload, 1234L);
        VidocqDevReloadLoop.acknowledge(reload, 5678L);

        assertEquals("5678\n", Files.readString(dir.resolve(".vidocq-dev-reload.ready")));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(".vidocq-dev-reload.ready"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFailureNeverStopsTheApplication(@TempDir Path dir) {
        assertDoesNotThrow(() -> VidocqDevReloadLoop.acknowledge(dir.resolve("absent/.vidocq-dev-reload"), 1L));
    }

    @Test
    void aBootThatShutsDownEndsTheWaitWithoutReload(@TempDir Path dir) {
        VidocqBootstrap bootstrap = VidocqBootstrap.create();
        bootstrap.shutdown();

        assertTrue(VidocqDevReloadLoop.awaitReloadSignal(bootstrap, dir.resolve(".vidocq-dev-reload"), 0L).isEmpty());
    }

    @Test
    void aMovedStampSignalsAReload(@TempDir Path dir) throws Exception {
        Path reload = Files.writeString(dir.resolve(".vidocq-dev-reload"), "");
        Files.setLastModifiedTime(reload, FileTime.fromMillis(42_000L));
        VidocqBootstrap bootstrap = VidocqBootstrap.create();
        try {
            assertEquals(OptionalLong.of(42_000L), VidocqDevReloadLoop.awaitReloadSignal(bootstrap, reload, 1L));
        } finally {
            bootstrap.shutdown();
        }
    }
    @Test
    void theSwapLineCarriesTheReason(@TempDir Path dir) throws Exception {
        Path reload = Files.writeString(dir.resolve(".vidocq-dev-reload"),
                "42\nswap MainView gained a method lambda$new$1\n");

        assertEquals(new VidocqDevReloadLoop.Answer(
                        "Hot reload - restarting on a fresh application layer: MainView gained a method lambda$new$1",
                        false),
                VidocqDevReloadLoop.answer(VidocqDevReloadLoop.request(reload), NEVER, false));
    }

    /** A host plugin older than the hot swap writes the nanoTime alone, and one without declarations does too. */
    @Test
    void aFileWithoutAModeLineKeepsTodaysLine(@TempDir Path dir) throws Exception {
        Path reload = Files.writeString(dir.resolve(".vidocq-dev-reload"), "42\n");
        var today = new VidocqDevReloadLoop.Answer("Hot reload - restarting on a fresh application layer", false);

        assertEquals(today, VidocqDevReloadLoop.answer(VidocqDevReloadLoop.request(reload), NEVER, false));
        assertEquals(today,
                VidocqDevReloadLoop.answer(VidocqDevReloadLoop.request(dir.resolve("absent")), NEVER, false));
    }

    @Test
    void aRedefinitionKeepsTheApplicationRunning() {
        var answer = VidocqDevReloadLoop.answer(redefine("app.MainView"),
                _ -> new ClassRedefinition.Done(List.of(MAIN_VIEW), List.of()), false);

        assertTrue(answer.keepsRunning());
        assertTrue(Pattern.matches("Hot reload - redefined 1 class in \\d+ ms, the application keeps running: MainView",
                answer.line()), answer.line());
    }

    @Test
    void severalClassesAreNamedByTheirBinaryNameWithoutPackage() {
        var answer = VidocqDevReloadLoop.answer(redefine("app.MainView", "app.MainView$Row"),
                _ -> new ClassRedefinition.Done(List.of(MAIN_VIEW, ROW), List.of()), false);

        assertTrue(answer.line().contains("redefined 2 classes in ")
                && answer.line().endsWith("the application keeps running: MainView, MainView$Row"), answer.line());
    }

    @Test
    void classesNotLoadedYetTakeTheNewCodeWhenFirstUsed() {
        var answer = VidocqDevReloadLoop.answer(redefine("app.MainView", "app.Helper"),
                _ -> new ClassRedefinition.Done(List.of(), List.of("app.MainView", "app.Helper")), false);

        assertEquals(new VidocqDevReloadLoop.Answer("Hot reload - 2 classes changed in method bodies, none of them"
                + " loaded yet: they load the new code when first used", true), answer);
    }

    @Test
    void aRefusalSwapsNamingTheJvmReason() {
        var answer = VidocqDevReloadLoop.answer(redefine("app.MainView"),
                _ -> new ClassRedefinition.Refused("class redefinition failed: attempted to add a method"), false);

        assertEquals(new VidocqDevReloadLoop.Answer("Hot reload - redefinition refused (class redefinition failed:"
                + " attempted to add a method): restarting on a fresh application layer", false), answer);
    }

    @Test
    void theBreakpointSentenceOnlyWithAJdwpAgent() {
        var done = new ClassRedefinition.Done(List.of(MAIN_VIEW), List.of());

        String debugged = VidocqDevReloadLoop.answer(redefine("app.MainView"), _ -> done, true).line();
        String plain = VidocqDevReloadLoop.answer(redefine("app.MainView"), _ -> done, false).line();

        assertTrue(debugged.endsWith("the application keeps running: MainView; breakpoints in MainView no longer stop:"
                + " set them again, or debug with -Dvidocq.dev.hotSwap=false"), debugged);
        assertFalse(plain.contains("breakpoints"), plain);
    }

    @Test
    void aRedefinitionTellsTheLoadedClassesWithTheirMethods() {
        var answer = VidocqDevReloadLoop.answer(redefine("app.MainView", "app.Helper"),
                _ -> new ClassRedefinition.Done(List.of(MAIN_VIEW), List.of("app.Helper")), false);

        assertEquals(Map.of(MAIN_VIEW, Set.of("get")), answer.redefined());
    }

    @Test
    void aSwapARefusalOrNoLoadedClassTellsNothing() {
        var swap = VidocqDevReloadLoop.answer(new ReloadRequest.Swap("MainView gained a method"), NEVER, false);
        var refused = VidocqDevReloadLoop.answer(redefine("app.MainView"),
                _ -> new ClassRedefinition.Refused("attempted to add a method"), false);
        var notLoaded = VidocqDevReloadLoop.answer(redefine("app.MainView"),
                _ -> new ClassRedefinition.Done(List.of(), List.of("app.MainView")), false);

        assertEquals(Map.of(), swap.redefined());
        assertEquals(Map.of(), refused.redefined());
        assertEquals(Map.of(), notLoaded.redefined());
    }

    @Test
    void aJdwpAgentIsSeenInTheInputArguments() {
        assertTrue(VidocqDevReloadLoop.jdwp(List.of("-Xmx1g",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")));
        assertTrue(VidocqDevReloadLoop.jdwp(List.of("-Xrunjdwp:transport=dt_socket")));
        assertFalse(VidocqDevReloadLoop.jdwp(List.of("-javaagent:/tmp/vidocq-runtime-dev-agent.jar")));
    }

    /** {@code app.MainView} and {@code app.MainView$Row}, as an application layer defines them. */
    private static final ClassLoader APP = new AppClasses("app.MainView", "app.MainView$Row");
    private static final Class<?> MAIN_VIEW = load("app.MainView");
    private static final Class<?> ROW = load("app.MainView$Row");

    private static Class<?> load(String name) {
        try {
            return APP.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /** Defines empty classes of the given names, built with the Class-File API. */
    private static final class AppClasses extends ClassLoader {

        private final Set<String> names;

        AppClasses(String... names) {
            super(VidocqDevReloadLoopTest.class.getClassLoader());
            this.names = Set.of(names);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!names.contains(name)) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = ClassFile.of().build(ClassDesc.of(name),
                    builder -> builder.withSuperclass(ConstantDescs.CD_Object));
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static final Function<ReloadRequest.Redefine, ClassRedefinition.Result> NEVER = _ -> {
        throw new AssertionError("a swap request redefines nothing");
    };

    private static ReloadRequest.Redefine redefine(String... names) {
        SequencedMap<String, SequencedSet<String>> methods = new LinkedHashMap<>();
        for (String name : names) {
            methods.put(name, new LinkedHashSet<>(List.of("get")));
        }
        return new ReloadRequest.Redefine(methods);
    }
    @Test
    void theTaskBeforeTheStopTellsTheListenersOfTheInstalledLayer() {
        var listeners = new StopNotifier();

        VidocqDevReloadLoop.beforeStop(listeners, ModuleLayer::boot).run();

        assertEquals(List.of(ModuleLayer.boot()), listeners.stopped);
    }

    @Test
    void withoutAnInstalledLayerTheTaskBeforeTheStopTellsNobody() {
        var listeners = new StopNotifier();

        VidocqDevReloadLoop.beforeStop(listeners, () -> null).run();

        assertEquals(List.of(), listeners.stopped);
    }

    /** Records the layers whose listeners were told of a stop. */
    private static final class StopNotifier implements ClassRedefinition.Notifier {

        private final List<ModuleLayer> stopped = new ArrayList<>();

        @Override
        public void redefined(ModuleLayer layer, SequencedMap<Class<?>, SequencedSet<String>> methodsByClass) {
            // only the stop matters here
        }

        @Override
        public void stopping(ModuleLayer layer) {
            stopped.add(layer);
        }

        @Override
        public void close() {
            // nothing started
        }
    }
}
