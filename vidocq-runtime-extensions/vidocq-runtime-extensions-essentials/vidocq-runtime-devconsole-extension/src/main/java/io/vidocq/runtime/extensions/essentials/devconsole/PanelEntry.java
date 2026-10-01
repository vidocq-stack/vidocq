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

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContributor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One panel of the console for one boot, as it is read once and shown on every poll: the boot facts of a section of
 * the report, and, for a {@link DevConsolePanel}, the panel to sample and its charts.
 *
 * @param id        the id of its contributor, the section's id
 * @param title     what the page calls it
 * @param section   its boot facts
 * @param panel     what writes its live values, or {@code null} for a contributor that is no panel
 * @param charts    what the page plots of them; empty when there is no panel or its {@code charts()} failed
 * @param actions   what the page may ask it to do: read in a dev launch only, empty in any other, when there is no
 *                  panel or its {@code actions()} failed; each id once, {@value #MAX_ACTIONS} at most
 * @param languages what the page's code editor may fetch of it: read in a dev launch only, with the actions; empty
 *                  in any other, when there is no panel, its {@code languages()} failed or named one id twice
 */
record PanelEntry(String id, String title, ReportSection section, DevConsolePanel panel, List<Chart> charts,
                  List<PanelAction> actions, List<PanelLanguage> languages) {

    /** The most actions of one panel the console shows. */
    static final int MAX_ACTIONS = 128;

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    PanelEntry {
        charts = List.copyOf(charts);
        actions = List.copyOf(actions);
        languages = List.copyOf(languages);
    }

    /**
     * The action {@code id} of this panel.
     *
     * @param id the id of the action, as the request names it
     * @return the action, or {@code null} when the panel offers none of that id
     */
    PanelAction action(String id) {
        for (PanelAction action : actions) {
            if (action.id().equals(id)) {
                return action;
            }
        }
        return null;
    }

    /**
     * The language {@code id} of this panel.
     *
     * @param id the id of the language, as the request names it
     * @return the language, or {@code null} when the panel offers none of that id
     */
    PanelLanguage language(String id) {
        for (PanelLanguage language : languages) {
            if (language.id().equals(id)) {
                return language;
            }
        }
        return null;
    }

    /**
     * The panel of a contributor of the report, from the section it wrote: a {@link DevConsolePanel} is live, any
     * other contributor shows its boot facts only.
     *
     * @param contributor the contributor, as the report called it
     * @param section     the section it wrote
     * @param dev         whether the boot is a dev launch, the only one whose panels offer actions and languages
     */
    static PanelEntry contributed(StartupReportContributor contributor, ReportSection section, boolean dev) {
        DevConsolePanel panel = contributor instanceof DevConsolePanel live ? live : null;
        return new PanelEntry(section.id(), title(contributor, section.id()), section, panel,
                charts(panel, section.id()), dev ? actions(panel, section.id()) : List.of(),
                dev ? languages(panel, section.id()) : List.of());
    }

    /**
     * One of the console's own panels: it writes its boot facts now, into a section of the console's own that never
     * logs, as a contributor writes its section of the report.
     *
     * @param panel the panel
     * @param mode  the launch mode of the boot
     */
    static PanelEntry builtIn(DevConsolePanel panel, LaunchMode mode) {
        String id = panel.id();
        String title = title(panel, id);
        RecordingSection section = new RecordingSection(id, title);
        try {
            panel.contribute(new ConsoleReportContext(mode), section);
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' failed to write its boot facts",
                    failed);
        }
        return new PanelEntry(id, title, section.toSection(), panel, charts(panel, id),
                mode == LaunchMode.DEV ? actions(panel, id) : List.of(),
                mode == LaunchMode.DEV ? languages(panel, id) : List.of());
    }

    private static String title(StartupReportContributor contributor, String id) {
        try {
            String title = contributor.title();
            return title == null || title.isBlank() ? id : title;
        } catch (RuntimeException | LinkageError failed) {
            return id;
        }
    }

    /** The charts of {@code panel}, read once per boot; none when it has none or they fail. */
    private static List<Chart> charts(DevConsolePanel panel, String id) {
        if (panel == null) {
            return List.of();
        }
        try {
            List<Chart> charts = panel.charts();
            return charts == null ? List.of() : charts.stream().filter(chart -> chart != null).toList();
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' has no charts: its charts() failed",
                    failed);
            return List.of();
        }
    }

    /**
     * The actions of {@code panel}, read once per boot and in a dev launch only: none when it has none or they
     * fail; an action whose id was already seen, and those past {@value #MAX_ACTIONS}, dropped.
     */
    private static List<PanelAction> actions(DevConsolePanel panel, String id) {
        if (panel == null) {
            return List.of();
        }
        try {
            List<PanelAction> declared = panel.actions();
            if (declared == null) {
                return List.of();
            }
            Set<String> ids = new HashSet<>();
            List<PanelAction> kept = declared.stream().filter(Objects::nonNull).filter(a -> ids.add(a.id()))
                    .limit(MAX_ACTIONS).toList();
            if (kept.size() < declared.size()) {
                LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' offers " + declared.size()
                        + " actions, the console keeps " + kept.size());
            }
            return kept;
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' has no actions: its actions() failed",
                    failed);
            return List.of();
        }
    }

    /**
     * The languages of {@code panel}, read once per boot and in a dev launch only: none when it has none or they fail,
     * and none, with a WARNING that says why, when two of them have one id.
     */
    private static List<PanelLanguage> languages(DevConsolePanel panel, String id) {
        if (panel == null) {
            return List.of();
        }
        try {
            List<PanelLanguage> declared = panel.languages();
            if (declared == null) {
                return List.of();
            }
            List<PanelLanguage> kept = declared.stream().filter(Objects::nonNull).toList();
            Set<String> ids = new HashSet<>();
            for (PanelLanguage language : kept) {
                if (!ids.add(language.id())) {
                    LOG.log(System.Logger.Level.WARNING, "Dev console panel '" + Texts.clean(id)
                            + "' offers the language '" + language.id() + "' twice: it is shown without languages");
                    return List.of();
                }
            }
            return kept;
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' has no languages: its languages() "
                    + "failed", failed);
            return List.of();
        }
    }
}
