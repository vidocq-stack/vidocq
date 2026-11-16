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

import io.vidocq.runtime.spi.devconsole.RedefinedClass;
import io.vidocq.runtime.spi.devconsole.Redefinition;
import io.vidocq.runtime.spi.devconsole.RedefinitionListener;

import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The {@code RedefinitionListener}s of the {@code -dev} companions, told what each redefinition changed (hot swap design
 * §5). One virtual thread calls them, one redefinition after the other, so a slow listener never holds the reload loop.
 *
 * <p>This class names the dev SPI's types: {@link ClassRedefinition#listeners} builds it only once the SPI module is in
 * the boot layer.
 */
final class RedefinitionListeners implements ClassRedefinition.Notifier {

    private final Function<ModuleLayer, List<RedefinitionListener>> load;
    private final Consumer<String> warn;
    private final BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
    private Thread caller;

    private RedefinitionListeners(Function<ModuleLayer, List<RedefinitionListener>> load, Consumer<String> warn) {
        this.load = load;
        this.warn = warn;
    }

    /** The listeners each layer provides through {@link ServiceLoader}, told on their own virtual thread. */
    static RedefinitionListeners start(Consumer<String> warn) {
        return start(layer -> load(layer, warn), warn);
    }

    /** @param load the listeners of a layer, loaded again at each redefinition */
    static RedefinitionListeners start(Function<ModuleLayer, List<RedefinitionListener>> load, Consumer<String> warn) {
        var listeners = new RedefinitionListeners(load, warn);
        listeners.caller = Thread.ofVirtual().name("vidocq-redefinition-listeners").start(listeners::callInOrder);
        return listeners;
    }

    @Override
    public void redefined(ModuleLayer layer, SequencedMap<Class<?>, SequencedSet<String>> methodsByClass) {
        var redefinition = new Redefinition(methodsByClass.entrySet().stream()
                .map(entry -> new RedefinedClass(entry.getKey(), entry.getValue())).toList());
        pending.add(() -> tell(layer, redefinition));
    }

    /** One redefinition after the other, until {@link #close}. */
    private void callInOrder() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                pending.take().run();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void tell(ModuleLayer layer, Redefinition redefinition) {
        for (RedefinitionListener listener : load.apply(layer)) {
            try {
                listener.redefined(redefinition);
            } catch (RuntimeException e) {
                warn.accept("Hot reload - the redefinition listener " + listener.getClass().getName() + " failed: " + e);
            }
        }
    }

    /** The listeners {@code layer} and its parents provide; one that cannot be created is logged and left out. */
    private static List<RedefinitionListener> load(ModuleLayer layer, Consumer<String> warn) {
        List<ServiceLoader.Provider<RedefinitionListener>> providers;
        try {
            providers = ServiceLoader.load(layer, RedefinitionListener.class).stream().toList();
        } catch (ServiceConfigurationError e) {
            warn.accept("Hot reload - the redefinition listeners cannot be listed: " + e.getMessage());
            return List.of();
        }
        List<RedefinitionListener> listeners = new ArrayList<>();
        for (ServiceLoader.Provider<RedefinitionListener> provider : providers) {
            try {
                listeners.add(provider.get());
            } catch (ServiceConfigurationError e) {
                warn.accept("Hot reload - the redefinition listener " + provider.type().getName()
                        + " cannot be created: " + e.getMessage());
            }
        }
        return listeners;
    }

    @Override
    public void close() {
        caller.interrupt();
    }
}
