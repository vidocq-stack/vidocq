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

import io.vidocq.vauban.classloader.VaubanClassLoader;

import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The redefinition of a {@code redefine} request of {@code vidocq:dev} (hot swap design §4): the bytes Vauban's loader
 * would define now, woven as the loaded classes are, handed to the JVM all or nothing.
 *
 * <p>The agent's own types stay in {@link AgentRedefiner}, which this class loads only once the agent module is in the
 * boot layer: in any other launch that module is absent, and so are its classes. The same goes for the dev SPI's types
 * and {@link RedefinitionListeners}.
 */
final class ClassRedefinition {

    /** The module of the agent {@code vidocq:dev} starts the child with. */
    static final String AGENT_MODULE = "io.vidocq.runtime.dev.agent";

    /** The module of the dev SPI, whose {@code RedefinitionListener} the {@code -dev} companions provide. */
    static final String DEV_SPI_MODULE = "io.vidocq.runtime.spi.devconsole";

    /** Redefines the loaded classes among names, all or nothing. */
    @FunctionalInterface
    interface Redefiner {
        Result redefine(ClassLoader loader, Collection<String> names, Function<String, byte[]> bytes);
    }

    /** What a redefinition did. */
    sealed interface Result permits Done, Refused {
    }

    /**
     * @param redefined the classes redefined
     * @param notLoaded the binary names not loaded yet, which load the new code when first used
     */
    record Done(List<Class<?>> redefined, List<String> notLoaded) implements Result {

        Done {
            redefined = List.copyOf(redefined);
            notLoaded = List.copyOf(notLoaded);
        }
    }

    /** Nothing was redefined; the reason is the JVM's, or why the bytes could not be read. */
    record Refused(String reason) implements Result {
    }

    /** The redefiner of a JVM started without the agent. */
    static final Redefiner NO_AGENT = (loader, names, bytes) -> new Refused("the hot swap agent is not installed");

    /** Tells the {@code -dev} companions what a redefinition changed (design §5), without naming the dev SPI's types. */
    interface Notifier extends AutoCloseable {

        /** No companion can listen: the dev SPI module is not in the boot layer. */
        Notifier NONE = new Notifier() {
            @Override
            public void redefined(ModuleLayer layer, SequencedMap<Class<?>, SequencedSet<String>> methodsByClass) {
                // nobody to tell
            }

            @Override
            public void stopping(ModuleLayer layer) {
                // nobody to tell
            }

            @Override
            public void close() {
                // nothing started
            }
        };

        /**
         * Returns at once: the listeners of {@code layer} run later, on their own thread.
         *
         * @param methodsByClass the loaded classes redefined, each with the methods whose code changed
         */
        void redefined(ModuleLayer layer, SequencedMap<Class<?>, SequencedSet<String>> methodsByClass);

        /** Tells the listeners of {@code layer} that the application stops, and returns once they have all answered. */
        void stopping(ModuleLayer layer);

        @Override
        void close();
    }

    /** The listeners of the {@code -dev} companions, or {@link Notifier#NONE} when {@code boot} lacks the dev SPI. */
    static Notifier listeners(ModuleLayer boot, Consumer<String> warn) {
        return boot.findModule(DEV_SPI_MODULE).isPresent() ? RedefinitionListeners.start(warn) : Notifier.NONE;
    }

    private ClassRedefinition() {
    }

    /** The agent, when the JVM started with it; empty in any other launch. */
    static Optional<Redefiner> agent() {
        if (ModuleLayer.boot().findModule(AGENT_MODULE).isEmpty() || !AgentRedefiner.installed()) {
            return Optional.empty();
        }
        return Optional.of(new AgentRedefiner());
    }

    /**
     * Reads the bytes of every class of the request from {@code loader}, then hands them to {@code redefiner}. A class
     * whose bytes cannot be read refuses the whole request before the JVM is asked anything.
     */
    static Result apply(ReloadRequest.Redefine request, VaubanClassLoader loader, Redefiner redefiner) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (String name : request.methodsByClass().keySet()) {
            try {
                Optional<byte[]> definition = loader.definitionBytes(name);
                if (definition.isEmpty()) {
                    return new Refused(name + " is not in the application's archives");
                }
                bytes.put(name, definition.get());
            } catch (UncheckedIOException e) {
                return new Refused("cannot read the new bytes of " + name + ": " + e.getCause().getMessage());
            }
        }
        return redefiner.redefine(loader, List.copyOf(bytes.keySet()), bytes::get);
    }
}
