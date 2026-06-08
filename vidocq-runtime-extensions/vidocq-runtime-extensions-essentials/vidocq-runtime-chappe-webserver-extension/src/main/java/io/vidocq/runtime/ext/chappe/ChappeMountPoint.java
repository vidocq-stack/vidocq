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
package io.vidocq.runtime.ext.chappe;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.WebSocketHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
 */
public final class ChappeMountPoint {

    private static volatile ChappeMountPoint instance;

    private final Map<String, Router.Builder> routers = new LinkedHashMap<>();
    private final Map<String, ChappeListener> listeners = new LinkedHashMap<>();
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
     * Declared a listener. Called by {@link ChappeServerBootstrap} at startup.
     */
    void declareListener(ChappeListener listener) {
        ensureOpen();
        listeners.put(listener.name(), listener);
        routers.computeIfAbsent(listener.name(), n -> Router.builder());
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
        return Collections.unmodifiableCollection(listeners.values());
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
