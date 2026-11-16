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
package io.vidocq.runtime.dev.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Redefines loaded classes in place, through the {@link Instrumentation} the JVM hands to {@link #premain}.
 *
 * <p>{@code vidocq:dev} puts this module on the child's module path, resolves it ({@code --add-modules}) and names
 * its jar in {@code -javaagent}: the system class loader then loads this class from the named module, so the reload
 * loop of the core and {@code premain} share one {@code HotSwap}.
 */
public final class HotSwap {

    private static volatile Instrumentation instrumentation;

    private HotSwap() {
    }

    /** Called by the JVM before {@code main}: keeps its {@code Instrumentation}. */
    public static void premain(String args, Instrumentation instrumentation) {
        HotSwap.instrumentation = instrumentation;
    }

    /** Whether the JVM started with this agent and can redefine classes. */
    public static boolean installed() {
        Instrumentation current = instrumentation;
        return current != null && current.isRedefineClassesSupported();
    }

    /**
     * Redefines the classes among {@code names} that {@code loader} has loaded, with {@code bytes.apply(name)}, all or
     * nothing: when the JVM refuses one, none is redefined. A class the loader has not loaded is left alone: it will
     * be defined from the new bytes when it is first used.
     *
     * @throws RedefinitionRefused when the JVM refuses the redefinition, with its reason, such as an added method
     * @throws IllegalStateException when the JVM did not start with this agent
     */
    public static Outcome redefine(ClassLoader loader, Collection<String> names, Function<String, byte[]> bytes)
            throws RedefinitionRefused {
        Instrumentation current = instrumentation;
        if (current == null) {
            throw new IllegalStateException("the hot swap agent is not installed: the JVM started without -javaagent");
        }
        var wanted = new LinkedHashSet<>(names);
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> type : current.getInitiatedClasses(loader)) {
            if (type.getClassLoader() == loader && wanted.contains(type.getName())) {
                loaded.put(type.getName(), type);
            }
        }
        List<ClassDefinition> definitions = new ArrayList<>();
        List<Class<?>> redefined = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        for (String name : wanted) {
            Class<?> type = loaded.get(name);
            if (type == null) {
                notLoaded.add(name);
            } else {
                definitions.add(new ClassDefinition(type, bytes.apply(name)));
                redefined.add(type);
            }
        }
        if (!definitions.isEmpty()) {
            try {
                current.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            } catch (ClassNotFoundException | UnmodifiableClassException | UnsupportedOperationException
                     | LinkageError e) {
                throw new RedefinitionRefused(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
        return new Outcome(List.copyOf(redefined), List.copyOf(notLoaded));
    }

    /**
     * What a redefinition did.
     *
     * @param redefined the classes redefined, in the order of the names asked for
     * @param notLoaded the names the loader had not loaded, left alone
     */
    public record Outcome(List<Class<?>> redefined, List<String> notLoaded) {
    }
}
