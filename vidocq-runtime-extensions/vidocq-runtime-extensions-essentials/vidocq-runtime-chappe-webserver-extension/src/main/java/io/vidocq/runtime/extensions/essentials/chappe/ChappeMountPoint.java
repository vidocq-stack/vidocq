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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.WebSocketHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single attachment point shared between extensions to contribute handlers to the Chappe engine.
 * <p>
 * The contributing extensions ({@code vidocq-servlet-chappe-extension},
 * {@code vidocq-rest-chappe-extension}, ...) call
 * {@link #mount(String, String, Handler)} during their {@code onStart} phase.
 * {@link ChappeServerBootstrap} then assembles the {@link Router} and starts a
 * {@link io.vidocq.chappe.api.Server Server} by listener.
 * </p>
 *
 * <p>Thread-safety: all contributions must be made during the phase
 * {@code onStart} (single-threaded orchestrated by {@code VidocqBootstrap}). The instance is
 * then frozen when the server starts.</p>
 *
 * <p>An extension may also bring a listener of its own, with
 * {@link #declareListener(ChappeListener, ListenerOptions)}: {@link ChappeServerBootstrap} starts it with
 * those of the configuration ({@code vidocq.chappe.listeners}).</p>
 */
public final class ChappeMountPoint {

    private static volatile ChappeMountPoint instance;

    /** Names the class that declares a listener, for the error when the configuration lists it too. */
    private static final StackWalker CALLER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final Map<String, Router.Builder> routers = new LinkedHashMap<>();
    private final Map<String, Declaration> listeners = new LinkedHashMap<>();
    private final List<Runnable> beforeStartHooks = new ArrayList<>();
    private final List<Runnable> afterStopHooks = new ArrayList<>();
    private volatile boolean frozen;

    ChappeMountPoint() {}

    /**
     * Accesses the single, non-null instance after starting {@link ChappeEngineExtension}.
     * <p>Access the singleton. Non-null after {@link ChappeEngineExtension} has started.</p>
     */
    public static ChappeMountPoint instance() {
        ChappeMountPoint i = instance;
        if (i == null) {
            throw new IllegalStateException(
                    "ChappeMountPoint not initialized — ChappeEngineExtension must run first");
        }
        return i;
    }

    static void install(ChappeMountPoint mp) {
        instance = mp;
    }

    static void uninstall() {
        instance = null;
    }

    /**
     * A listener and how to start it.
     *
     * @param listener the listener
     * @param options how {@link ChappeServerBootstrap} starts it, {@link ListenerOptions#DEFAULTS} for the
     *        configuration's
     * @param owner the simple name of the class that declared it, {@code null} for a listener of the
     *        configuration
     */
    record Declaration(ChappeListener listener, ListenerOptions options, String owner) {}

    /**
     * Declares a listener of the configuration ({@code vidocq.chappe.listeners}). Called by
     * {@link ChappeServerBootstrap} at startup, after every extension declared its own: a name an extension
     * declared cannot be listed there too. A name the configuration lists twice is declared once, from the
     * same keys.
     *
     * @throws IllegalStateException when an extension declared a listener of that name, or once frozen
     */
    void declareListener(ChappeListener listener) {
        Objects.requireNonNull(listener, "listener");
        ensureOpen();
        Declaration existing = listeners.get(listener.name());
        if (existing != null) {
            if (existing.owner() != null) {
                throw declaredTwice(listener.name(), existing.owner());
            }
            return;
        }
        declare(new Declaration(listener, ListenerOptions.DEFAULTS, null));
    }

    /**
     * Declares a listener of an extension's own, next to the application's: {@link ChappeServerBootstrap}
     * starts one server for it, with {@code options}, and the extension mounts on it by its name, like on any
     * other listener. Callable until the mount point is frozen, from {@code onStart} of an extension that runs
     * after {@link ChappeEngineExtension} (priority 100) and before {@link ChappeServerBootstrap} (10,000).
     *
     * <p>A name has one owner: the listener belongs to the extension, and {@code vidocq.chappe.listeners} must
     * not list it; the boot fails if it does, naming the class that declared it. The listener named
     * {@value ChappeListener#DEFAULT} is the application's.
     *
     * @param listener the listener; its port may be {@code 0}, for a free one
     * @param options how to start it, {@link ListenerOptions#DEFAULTS} to start it like a configured one
     * @throws IllegalArgumentException for the listener named {@value ChappeListener#DEFAULT}
     * @throws IllegalStateException when the name is already declared, or once frozen
     */
    public void declareListener(ChappeListener listener, ListenerOptions options) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(options, "options");
        ensureOpen();
        if (ChappeListener.DEFAULT.equals(listener.name())) {
            throw new IllegalArgumentException("the listener '" + ChappeListener.DEFAULT
                    + "' is the application's, configured by vidocq.chappe.listener.default.*; declare another name");
        }
        Class<?> caller = CALLER.getCallerClass();
        String owner = caller.getSimpleName().isEmpty() ? caller.getName() : caller.getSimpleName();
        Declaration existing = listeners.get(listener.name());
        if (existing != null) {
            throw existing.owner() == null
                    ? declaredTwice(listener.name(), owner)
                    : new IllegalStateException("listener '" + listener.name()
                            + "' is already declared by an extension (" + existing.owner() + ")");
        }
        declare(new Declaration(listener, options, owner));
    }

    private void declare(Declaration declaration) {
        listeners.put(declaration.listener().name(), declaration);
        routers.computeIfAbsent(declaration.listener().name(), n -> Router.builder());
    }

    private static IllegalStateException declaredTwice(String name, String owner) {
        return new IllegalStateException("listener '" + name + "' is declared by an extension (" + owner
                + "); remove it from vidocq.chappe.listeners");
    }

    /**
     * Mounts a handler on the default listener, under the given prefix.
     * <p>Mount a handler on the default listener under the given prefix.</p>
     */
    public void mount(String prefix, Handler handler) {
        mount(ChappeListener.DEFAULT, prefix, handler, true);
    }

    /**
     * Mounts a handler on a named listener (path stripping).
     * <p>Mount a handler on a named listener.</p>
     */
    public void mount(String listenerName, String prefix, Handler handler) {
        mount(listenerName, prefix, handler, true);
    }

    /**
     * Mounts a handler on a named listener, controlling prefix stripping.
     * With {@code stripPrefix=false} the prefix is used for routing only and the handler sees the
     * full path (see {@link Router.Builder#mount(String, Handler, boolean)}).
     */
    public void mount(String listenerName, String prefix, Handler handler, boolean stripPrefix) {
        Objects.requireNonNull(listenerName, "listenerName");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(handler, "handler");
        ensureOpen();
        routers.computeIfAbsent(listenerName, n -> Router.builder()).mount(prefix, handler, stripPrefix);
    }

    /**
     * Registers a WebSocket endpoint (RFC 6455) on a named listener at the given path pattern
     * (e.g. {@code /ws/rooms/{pin}}). Thin convenience over {@link #router(String)}.webSocket(…);
     * used by {@code ChappeMountConfigExtension} for declarative {@code type=websocket} mounts.
     */
    public void webSocket(String listenerName, String pattern, WebSocketHandler handler) {
        Objects.requireNonNull(listenerName, "listenerName");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(handler, "handler");
        ensureOpen();
        routers.computeIfAbsent(listenerName, n -> Router.builder()).webSocket(pattern, handler);
    }

    /**
     * Direct access to the {@link Router.Builder} of a listener, for detailed contributions
     * (routes by method, groups, filters).
     * <p>Direct access to a listener's {@link Router.Builder} for fine-grained contributions.</p>
     */
    public Router.Builder router(String listenerName) {
        Objects.requireNonNull(listenerName, "listenerName");
        ensureOpen();
        return routers.computeIfAbsent(listenerName, n -> Router.builder());
    }

    /**
     * Hook executed just before starting each server.
     * <p>Hook executed right before each server starts.</p>
     */
    public void addBeforeStartHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        ensureOpen();
        beforeStartHooks.add(hook);
    }

    /**
     * Hook executed just after each server shuts down.
     * <p>Hook executed right after each server stops.</p>
     */
    public void addAfterStopHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        afterStopHooks.add(hook);
    }

    // --- Internal accessors for ChappeServerBootstrap ---

    Collection<ChappeListener> listeners() {
        return listeners.values().stream().map(Declaration::listener).toList();
    }

    /** Every declared listener, in declaration order: the extensions' first, then the configuration's. */
    List<Declaration> declarations() {
        return List.copyOf(listeners.values());
    }

    Router buildRouter(String listenerName) {
        Router.Builder b = routers.get(listenerName);
        return b == null ? Router.builder().build() : b.build();
    }

    List<Runnable> beforeStartHooks() {
        return List.copyOf(beforeStartHooks);
    }

    List<Runnable> afterStopHooks() {
        return List.copyOf(afterStopHooks);
    }

    void freeze() {
        this.frozen = true;
    }

    private void ensureOpen() {
        if (frozen) {
            throw new IllegalStateException(
                    "ChappeMountPoint is frozen — cannot contribute after server start");
        }
    }
}
