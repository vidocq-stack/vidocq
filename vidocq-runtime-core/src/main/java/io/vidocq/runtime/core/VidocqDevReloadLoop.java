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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Dev-mode hot reload by <b>application-layer re-creation</b>, in the same JVM: when the
 * host (the {@code vidocq:dev} mojo) signals a reload — it touches the file named by
 * {@value #RELOAD_FILE_PROPERTY} after a successful recompile — the running runtime is
 * shut down, the application {@link ModuleLayer} and its Vauban class loader are
 * discarded, and a fresh layer over the recompiled archives boots. No process respawn:
 * the JIT stays warm, dev services and debugger sessions survive, and the old layer's
 * classes become collectable once the new container drops the last reference.
 *
 * <p>Requires the universal-loader mode ({@code -Dvidocq.app.path=…}): the application
 * classes must live in the re-creatable layer, not on the JVM module path (those classes
 * are loaded once and for all). {@code -Dvidocq.app.main} is intentionally ignored here —
 * a user main would have to be re-entered on every cycle; the runtime boots directly.
 *
 * <p>A normal shutdown (Ctrl+C / SIGTERM → the {@code vidocq-shutdown} hook) still wins:
 * the loop polls the bootstrap's shutdown latch between signal checks and exits cleanly.
 */
final class VidocqDevReloadLoop {

    /** Path of the file the host touches to request a reload. */
    static final String RELOAD_FILE_PROPERTY = "vidocq.dev.reload.file";

    private static final System.Logger LOG = System.getLogger(VidocqDevReloadLoop.class.getName());
    private static final long POLL_MILLIS = 200;

    /**
     * Suffix of the file the loop writes once a boot completed, next to the reload file: it holds the stamp of the
     * signal that boot answered, so that the host starts the tests of that change after it (#122).
     */
    static final String READY_SUFFIX = ".ready";

    private VidocqDevReloadLoop() {}

    static void run(Path reloadFile, String[] args) {
        if (!System.getProperty(VidocqAppLayer.APP_MAIN_PROPERTY, "").isBlank()) {
            LOG.log(System.Logger.Level.INFO,
                    "Hot-reload mode: ignoring " + VidocqAppLayer.APP_MAIN_PROPERTY
                            + " (the runtime boots directly on every cycle)");
        }
        long lastSignal = signalStamp(reloadFile);
        ClassRedefinition.Redefiner agent = ClassRedefinition.agent().orElse(ClassRedefinition.NO_AGENT);
        boolean jdwp = jdwp(ManagementFactory.getRuntimeMXBean().getInputArguments());
        Function<ReloadRequest.Redefine, ClassRedefinition.Result> redefine = request -> Optional.ofNullable(
                VidocqAppLayer.installedLoader())
                .map(loader -> ClassRedefinition.apply(request, loader, agent))
                .orElseGet(() -> new ClassRedefinition.Refused("no application layer is installed"));
        try (ClassRedefinition.Notifier listeners = ClassRedefinition.listeners(ModuleLayer.boot(),
                line -> LOG.log(System.Logger.Level.WARNING, line))) {
            serve(reloadFile, lastSignal, redefine, jdwp, listeners);
        }
    }

    private static void serve(Path reloadFile, long firstSignal,
            Function<ReloadRequest.Redefine, ClassRedefinition.Result> redefine, boolean jdwp,
            ClassRedefinition.Notifier listeners) {
        long lastSignal = firstSignal;
        while (true) {
            VidocqAppLayer.installIfConfigured();
            VidocqBootstrap bootstrap = VidocqBootstrap.create()
                    .beforeStop(beforeStop(listeners, VidocqAppLayer::installedLayer)).configure();
            // every cycle registers its own bootstrap: Vidocq.shutdown() must reach the running one
            Vidocq.register(bootstrap);
            bootstrap.start();

            acknowledge(reloadFile, lastSignal);

            Answer answer;
            do {
                OptionalLong reload = awaitReloadSignal(bootstrap, reloadFile, lastSignal);
                if (reload.isEmpty()) {
                    return; // normal shutdown (hook, or programmatic)
                }
                lastSignal = reload.getAsLong();
                answer = answer(request(reloadFile), redefine, jdwp);
                LOG.log(System.Logger.Level.INFO, answer.line());
                if (answer.keepsRunning()) {
                    acknowledge(reloadFile, lastSignal);
                    ModuleLayer layer = VidocqAppLayer.installedLayer();
                    if (layer != null && !answer.redefined().isEmpty()) {
                        listeners.redefined(layer, answer.redefined());
                    }
                }
            } while (answer.keepsRunning());

            long t0 = System.nanoTime();
            bootstrap.shutdown();
            VidocqAppLayer.resetForReload();
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            LOG.log(System.Logger.Level.INFO,
                    "Hot reload - previous deployment stopped in " + elapsed + " ms");
        }
    }

    /**
     * The task each boot runs before it stops, for a swap or for good: the listeners of the layer installed then
     * end what they keep open while the application still serves requests; none when no layer is installed.
     */
    static Runnable beforeStop(ClassRedefinition.Notifier listeners, Supplier<ModuleLayer> installed) {
        return () -> {
            ModuleLayer layer = installed.get();
            if (layer != null) {
                listeners.stopping(layer);
            }
        };
    }

    /**
     * Waits until {@code bootstrap} shuts down or the stamp of {@code reloadFile} moves past {@code lastSignal}.
     *
     * @return the new stamp when a reload is signalled, empty when the bootstrap shut down first
     */
    static OptionalLong awaitReloadSignal(VidocqBootstrap bootstrap, Path reloadFile, long lastSignal) {
        while (!bootstrap.awaitShutdown(POLL_MILLIS)) {
            long stamp = signalStamp(reloadFile);
            if (stamp != lastSignal) {
                return OptionalLong.of(stamp);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * What the loop does with one signal: the line it logs, and whether the application keeps running (hot swap
     * design §4). A redefinition keeps it running; a swap request, or a redefinition that is refused, swaps the layer.
     *
     * @param redefined the loaded classes a redefinition changed, each with its changed methods, for the
     *                  {@code -dev} companions (design §5); empty otherwise
     */
    record Answer(String line, boolean keepsRunning, SequencedMap<Class<?>, SequencedSet<String>> redefined) {

        Answer {
            redefined = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(redefined));
        }

        Answer(String line, boolean keepsRunning) {
            this(line, keepsRunning, new LinkedHashMap<>());
        }
    }

    private static final String SWAP = "Hot reload - restarting on a fresh application layer";

    /** The request of the reload file; a file that cannot be read asks for today's swap. */
    static ReloadRequest request(Path reloadFile) {
        try {
            return ReloadRequest.parse(Files.readString(reloadFile));
        } catch (IOException _) {
            return new ReloadRequest.Swap("");
        }
    }

    /**
     * The answer to {@code request}: a swap, with the reason the host wrote ({@code Hot reload - restarting on a fresh
     * application layer: MainView gained a method lambda$new$1}, or today's line without one), or the redefinition
     * {@code redefine} does.
     *
     * @param jdwp whether a JDWP agent is loaded: the JVM clears the breakpoints of a redefined class (design §7)
     */
    static Answer answer(ReloadRequest request, Function<ReloadRequest.Redefine, ClassRedefinition.Result> redefine,
            boolean jdwp) {
        return switch (request) {
            case ReloadRequest.Swap swap -> new Answer(swap.reason().isEmpty() ? SWAP : SWAP + ": " + swap.reason(),
                    false);
            case ReloadRequest.Redefine redefinition -> {
                long t0 = System.nanoTime();
                ClassRedefinition.Result result = redefine.apply(redefinition);
                long millis = (System.nanoTime() - t0) / 1_000_000;
                yield switch (result) {
                    case ClassRedefinition.Refused(String reason) -> new Answer("Hot reload - redefinition refused ("
                            + reason + "): restarting on a fresh application layer", false);
                    case ClassRedefinition.Done done -> new Answer(redefinedLine(done, millis, jdwp), true,
                            methodsByClass(done, redefinition));
                };
            }
        };
    }

    private static SequencedMap<Class<?>, SequencedSet<String>> methodsByClass(ClassRedefinition.Done done,
            ReloadRequest.Redefine request) {
        SequencedMap<Class<?>, SequencedSet<String>> methods = new LinkedHashMap<>();
        for (Class<?> type : done.redefined()) {
            methods.put(type, request.methodsByClass().getOrDefault(type.getName(), new LinkedHashSet<>()));
        }
        return methods;
    }

    private static String redefinedLine(ClassRedefinition.Done done, long millis, boolean jdwp) {
        if (done.redefined().isEmpty()) {
            return "Hot reload - " + classes(done.notLoaded().size()) + " changed in method bodies, none of them loaded"
                    + " yet: they load the new code when first used";
        }
        String names = done.redefined().stream().map(Class::getName).map(VidocqDevReloadLoop::withoutPackage)
                .collect(Collectors.joining(", "));
        String line = "Hot reload - redefined " + classes(done.redefined().size()) + " in " + millis
                + " ms, the application keeps running: " + names;
        return jdwp ? line + "; breakpoints in " + names
                + " no longer stop: set them again, or debug with -Dvidocq.dev.hotSwap=false" : line;
    }

    private static String classes(int count) {
        return count + (count == 1 ? " class" : " classes");
    }

    private static String withoutPackage(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    /** Whether the JVM arguments load a JDWP agent, as {@code vidocq:dev} does by default. */
    static boolean jdwp(List<String> inputArguments) {
        return inputArguments.stream().anyMatch(argument -> argument.startsWith("-agentlib:jdwp")
                || argument.startsWith("-Xrunjdwp"));
    }

    /** {@code <reload file>.ready}, next to it. */
    static Path readyFile(Path reloadFile) {
        return reloadFile.resolveSibling(reloadFile.getFileName() + READY_SUFFIX);
    }

    /**
     * Tells the host that the boot answering the signal {@code stamp} completed: the stamp is written to
     * {@link #readyFile}, atomically. Never fails the application: a failure is logged at DEBUG, and the host then
     * runs the tests after its own timeout.
     */
    static void acknowledge(Path reloadFile, long stamp) {
        Path ready = readyFile(reloadFile);
        try {
            Path tmp = Files.createTempFile(ready.toAbsolutePath().getParent(), ".vidocq-dev-reload", ".tmp");
            try {
                Files.writeString(tmp, stamp + "\n");
                Files.move(tmp, ready, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException failed) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot write " + ready + ": " + failed.getMessage());
        }
    }

    private static long signalStamp(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException _) {
            return 0L;
        }
    }
}
