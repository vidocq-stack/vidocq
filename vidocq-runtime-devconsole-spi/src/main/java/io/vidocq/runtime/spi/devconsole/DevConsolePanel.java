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
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.report.StartupReportContributor;

import java.util.List;

/**
 * A section of the startup report that the dev console also shows live: the facts of the boot once, then values
 * sampled on every poll of the page, some of them drawn as charts.
 *
 * <p><b>One contract, two renderings.</b> A panel is a {@link StartupReportContributor}. What its
 * {@link #contribute contribute} writes, once per boot, is both its section of the report logged at the end of the
 * boot and the boot facts of its panel in the console. On top of that, {@link #sample sample} writes the values that
 * change while the application runs, and {@link #charts charts} says which of them the page plots. Every section of
 * the report is shown by the console, as boot facts; a contributor that implements this interface adds the live
 * part.
 *
 * <p><b>Where it lives.</b> In the runtime extension of its brick, the module under {@code vidocq-runtime-extensions}
 * that adapts a component, such as Mansart or Knock, to Vidocq. That module is the indirection between the two: it
 * configures the component, adapts it, and reads what it publishes. A panel is never written in the component, which
 * stays usable without Vidocq and depends on no Vidocq SPI, and never ships as an artifact of its own. The extension
 * module depends on {@code vidocq-runtime-devconsole-spi} and adds {@code requires io.vidocq.runtime.spi.devconsole;},
 * a hard requirement since a class does not load without the interfaces it implements; this module is small and
 * brings only {@code io.vidocq.runtime.spi} with it.
 *
 * <p><b>How the console finds it.</b> There is nothing new to declare. The console reads the contributors the boot
 * called, {@link io.vidocq.runtime.spi.report.StartupReportView#contributors()}, and keeps those that implement this
 * interface, the very instances. A panel is therefore declared as any contributor is:
 * <ul>
 *   <li>the extension class implements it, {@code class PoolExtension implements VidocqExtension, DevConsolePanel},
 *       and is found with no second declaration;</li>
 *   <li>an extension module with no {@code VidocqExtension} class, a wrapper that only brings its component in,
 *       gets one class that implements it, declared as a {@link StartupReportContributor} service twice:
 *       {@code provides io.vidocq.runtime.spi.report.StartupReportContributor with …} in its {@code module-info},
 *       and a {@code META-INF/services/io.vidocq.runtime.spi.report.StartupReportContributor} file for the class
 *       path, where the JDK ignores the former.</li>
 * </ul>
 *
 * <p><b>Identity and order.</b> The panel's id is {@link #id()}, stable and lowercase, such as {@code mansart-pool},
 * and unique through the report's own rules; {@code startup}, {@code cdi}, {@code jvm} and {@code devconsole} are the
 * console's. Its title is {@link #title()}. The console shows its own {@code startup} panel first, then the
 * contributed ones in the order of the report, then its {@code cdi} and {@code jvm} panels.
 *
 * <p><b>State.</b> {@link #sample sample} runs on the console's request threads, virtual threads, while the extension
 * may be stopping for a dev reload. What it reads is therefore published in a {@code volatile} field that holds an
 * immutable snapshot, assigned once complete, and cleared first thing in {@code onStop}, before what it refers to is
 * closed; {@code sample} reads that field once, into a local variable. A static field outlives a dev reload, since
 * this SPI and the extensions stay in the boot layer while the application is loaded again: it never holds a
 * {@link Class}, a bean or any other object of the application, which would keep the previous application in memory.
 *
 * <p><b>Secrets and configuration.</b> The page is served to a browser and the report goes to the log: a panel writes
 * a secret, such as a password or a token, only as
 * {@link io.vidocq.runtime.spi.report.StartupReportSection#secret(String, boolean) secret(key, configured)}, which
 * says whether it is set and never what it is, and {@code sample} never writes one. A configuration value that may
 * carry one, such as a JDBC URL, is written only when
 * {@link io.vidocq.runtime.spi.report.StartupReportContext#launchMode() launchMode()} is
 * {@link io.vidocq.runtime.spi.report.LaunchMode#DEV DEV}, with its credentials removed; outside development, the
 * panel writes what it is, such as the database kind.
 *
 * <p>For example, the panel of a cache in the extension that brings it to Vidocq:
 * <pre>{@code
 * public final class AcmeCacheExtension implements VidocqExtension, DevConsolePanel {
 *
 *     private static final List<Chart> CHARTS = List.of(
 *             new Chart("entries", "Entries", List.of(Series.area("entries"), Series.ceiling("entries"))),
 *             new Chart("lookups", "Lookups", List.of(Series.rate("hits"), Series.rate("misses"))));
 *
 *     private volatile AcmeCache cache;   // assigned once opened, cleared first in onStop
 *
 *     public String name() { return "acme-cache"; }
 *
 *     public void onStart(ExtensionContext context) { cache = AcmeCache.open(context.config()); }
 *
 *     public void onStop() {
 *         AcmeCache opened = cache;
 *         cache = null;
 *         if (opened != null) opened.close();
 *     }
 *
 *     public String id() { return "acme-cache"; }
 *
 *     public String title() { return "Acme cache"; }
 *
 *     public void contribute(StartupReportContext context, StartupReportSection section) {
 *         AcmeCache c = cache;
 *         section.summary(c == null ? "not started" : c.capacity() + " entries max");
 *     }
 *
 *     public List<Chart> charts() { return CHARTS; }
 *
 *     public void sample(PanelSample out) {
 *         AcmeCache c = cache;
 *         if (c == null) return;
 *         AcmeCacheStats stats = c.stats();   // counters in memory, read without a lock
 *         out.gauge("entries", stats.size(), c.capacity(), Unit.COUNT)
 *            .counter("hits", stats.hits(), Unit.COUNT)
 *            .counter("misses", stats.misses(), Unit.COUNT);
 *     }
 * }
 * }</pre>
 */
public interface DevConsolePanel extends StartupReportContributor {

    /**
     * The charts the page draws from this panel's successive samples. Read once per boot, after the report is
     * written, so it may depend on what the boot found. A chart is repeated once per
     * {@linkplain PanelSample#group group} that holds at least one of its keys; a value that no chart plots is still
     * shown, as a number. A {@link RuntimeException} or a {@link LinkageError} thrown here leaves the panel without
     * charts.
     *
     * @return the charts, in the order the page shows them, each with its own id; none by default
     */
    default List<Chart> charts() {
        return List.of();
    }

    /**
     * Writes the current values of this panel. Called by the dev console on every poll of its page, a
     * {@code GET /api/snapshot} about once a second per open tab: on the request thread, possibly on several at
     * once, and only once the report of this boot is written.
     *
     * <p>It reads memory only: no I/O, no network, no blocking call, no lock the application may hold, no bean
     * created, nothing logged. It should take well under a millisecond; above 5 ms the console flags the sample
     * {@code slow}. A value that only I/O could give is left out, or written {@link PanelSample#absent absent} with
     * the reason.
     *
     * <p>A {@link RuntimeException} or a {@link LinkageError} thrown here, an invalid key included, drops this sample
     * only. The page shows the simple name of the exception's class, never its message, which may carry a secret,
     * and the boot facts of the panel stay; the console logs one {@code VIDOCQ-DEVC-005} WARNING per panel and boot,
     * and calls the panel again on the next poll.
     *
     * @param sample where the values go, valid during this call only
     */
    void sample(PanelSample sample);
}
