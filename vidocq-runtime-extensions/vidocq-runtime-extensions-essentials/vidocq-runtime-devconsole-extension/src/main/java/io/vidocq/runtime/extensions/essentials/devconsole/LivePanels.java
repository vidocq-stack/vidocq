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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.LivePanel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The live panels of this boot, from the {@code -dev} modules {@code vidocq:dev} added (Vidocq/vidocq#143), by the
 * id of the section each one makes live. The first panel found for an id wins: a second one is
 * {@value #DUPLICATE}. Started once, the first time the console reads the written report; stopped once, with the
 * console. A panel whose {@code start} throws is dropped: its section stays static.
 */
final class LivePanels {

    /** Two live panels for one section id: the second is skipped. */
    static final String DUPLICATE = "VIDOCQ-DEVC-007";
    /** A live panel whose section is missing from the report: not shown. */
    static final String ORPHAN = "VIDOCQ-DEVC-008";
    /** A section whose contributor is itself a panel, with a live panel for the same id: the live panel wins. */
    static final String DOUBLE = "VIDOCQ-DEVC-009";

    static final LivePanels NONE = new LivePanels(Map.of(), warning -> {});

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    private final Map<String, LivePanel> byId;
    private final Consumer<String> warn;
    private boolean started;
    private boolean stopped;

    private LivePanels(Map<String, LivePanel> byId, Consumer<String> warn) {
        this.byId = byId;
        this.warn = warn;
    }

    /** The live panels the service loader finds from {@code loader}, warnings to the console's logger. */
    static LivePanels load(ClassLoader loader) {
        List<LivePanel> found = new ArrayList<>();
        try {
            ServiceLoader.load(LivePanel.class, loader).forEach(found::add);
        } catch (java.util.ServiceConfigurationError broken) {
            LOG.log(System.Logger.Level.WARNING, "Dev console: a live panel could not be loaded: "
                    + broken.getClass().getName());
        }
        return of(found, message -> LOG.log(System.Logger.Level.WARNING, message));
    }

    static LivePanels of(List<? extends LivePanel> panels, Consumer<String> warn) {
        Map<String, LivePanel> byId = new LinkedHashMap<>();
        for (LivePanel panel : panels) {
            String id = panel.id();
            LivePanel first = byId.putIfAbsent(id, panel);
            if (first != null) {
                warn.accept("[" + DUPLICATE + "] Two live panels for the section '" + Texts.clean(id) + "': "
                        + first.getClass().getName() + " is kept, " + panel.getClass().getName() + " is skipped");
            }
        }
        return new LivePanels(byId, warn);
    }

    /** The live panel of the section {@code id}, if any is loaded and running. */
    synchronized Optional<LivePanel> forSection(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    synchronized Set<String> ids() {
        return Set.copyOf(byId.keySet());
    }

    /** Starts every panel, once; one whose start throws is dropped, with a WARNING. */
    synchronized void startAll(ExtensionContext context) {
        if (started || stopped) {
            return;
        }
        started = true;
        for (var it = byId.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            try {
                entry.getValue().start(context);
            } catch (RuntimeException | LinkageError failed) {
                it.remove();
                warn.accept("Dev console: the live panel '" + Texts.clean(entry.getKey()) + "' failed to start: "
                        + failed.getClass().getName());
            }
        }
    }

    /** Stops every started panel, once. */
    synchronized void stopAll() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (!started) {
            return;
        }
        for (LivePanel panel : byId.values()) {
            try {
                panel.stop();
            } catch (RuntimeException | LinkageError ignored) {
                // stopping never fails the console's own stop
            }
        }
    }

    /** Reports the panels no section of {@code sectionIds} took, {@value #ORPHAN}, once per boot. */
    synchronized void reportOrphans(Set<String> sectionIds) {
        for (String id : byId.keySet()) {
            if (!sectionIds.contains(id)) {
                warn.accept("[" + ORPHAN + "] The live panel '" + Texts.clean(id)
                        + "' has no section in the startup report: not shown");
            }
        }
    }

    /** {@value #DOUBLE}: the extension of {@code id} also ships its own panel. */
    void reportDouble(String id, Object contributor) {
        warn.accept("[" + DOUBLE + "] The section '" + Texts.clean(id) + "' is written by " + contributor.getClass()
                .getName() + ", itself a panel, and a live panel makes it live: the live panel wins; the extension"
                + " ships its panel twice");
    }
}
