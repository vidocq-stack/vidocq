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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.Verbosity;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Finds the {@link StartupReportContributor}s of a boot and has each write its section, at every level of the
 * report, {@code off} included: only the rendering depends on the level, and an anomaly is never lost.
 *
 * <p><b>Discovery.</b> The extensions that are contributors come first, in the order they started; then the
 * services of {@link ServiceLoader#load(Class, ClassLoader)} on the context class loader, by
 * {@link StartupReportContributor#order()} then {@link StartupReportContributor#id()}. The application layer of
 * {@code Vidocq.run} can load a library twice, so the service loader may find one contributor class in two
 * layers: the providers are told apart by class name, the first one found kept, before any of them is created.
 * The same class twice is a twin and is skipped silently; the same id from two classes is
 * {@value StartupAnomalies#DUPLICATE_ID}, and so is the id of a section the core writes itself.
 *
 * <p><b>Isolation.</b> A contributor that cannot be loaded, created or identified, or whose
 * {@code contribute} throws a {@link RuntimeException} or a {@link LinkageError}, is
 * {@value StartupAnomalies#CONTRIBUTOR_FAILED}, logged with its stack trace: its section is dropped, the anomalies
 * it already reported stay, the boot goes on. Any other {@link Error} goes through.
 *
 * <p><b>Calls.</b> On the booting thread, one after the other, each timed; the routes and listeners of every
 * section are joined once they all ran, so that the order of the sections does not matter.
 */
public final class StartupContributors {

    /**
     * The ids no contributor may take: those of the sections the core writes itself, and those of the dev
     * console's own panels, {@code startup}, {@code config}, {@code cdi}, {@code logs} and {@code jvm}, which it
     * shows next to the contributed ones.
     */
    static final Set<String> CORE_IDS = Set.of("launch", "vidocq", "phases", CoreSections.LAYER,
            CoreSections.CONFIGURATION, CoreSections.EXTENSIONS, "anomalies", "startup", "config", "cdi", "logs",
            "jvm");
    /**
     * How many providers the service loader may fail to load before discovery stops: a service file that cannot
     * be read fails again on every attempt.
     */
    static final int MAX_DISCOVERY_FAILURES = 50;

    private StartupContributors() {}

    /**
     * A contributor to call, found and identified.
     *
     * @param instance  the contributor
     * @param id        its {@link StartupReportContributor#id() id}
     * @param title     its {@link StartupReportContributor#title() title}
     * @param className the binary name of its class
     */
    record Contributor(StartupReportContributor instance, String id, String title, String className) {}

    /**
     * What the contributors of a boot wrote: their sections, and the contributors that wrote them, the very
     * instances, in the same order. A contributor that failed has neither.
     *
     * @param sections     the sections, in the order of the report
     * @param contributors the contributors whose sections these are, one per section
     */
    public record Contributed(List<Section> sections, List<StartupReportContributor> contributors) {

        public Contributed {
            sections = List.copyOf(sections);
            contributors = List.copyOf(contributors);
        }
    }

    /**
     * Finds the contributors of a boot, has each write its section, and returns the sections in the order of the
     * report, with the contributors that wrote them. Never throws for a contributor: see the class description.
     *
     * @param extensions the extensions, in the order they started
     * @param loader     the loader whose services are looked up: the context class loader of the boot
     * @param verbosity  the level the contributors are given: that of the report, or {@code detailed} when every
     *                   row is wanted whatever the report shows, as for the dev console
     * @param launchMode the launch mode of the boot
     * @param beans      the bean manager of the container, or {@code null}
     * @param recorder   the recorder of the boot, which logs and keeps the anomalies
     */
    public static Contributed contribute(List<? extends VidocqExtension> extensions, ClassLoader loader,
                                         Verbosity verbosity, LaunchMode launchMode, BeanManager beans,
                                         StartupRecorder recorder) {
        return call(discover(extensions, loader, recorder), new ContributorContext(verbosity, launchMode, beans),
                recorder);
    }

    // ------------------------------------------------------------------------------------------ discovery

    /** The contributors to call, in the order of the report, each class and each id once. */
    static List<Contributor> discover(List<? extends VidocqExtension> extensions, ClassLoader loader,
                                      StartupRecorder recorder) {
        List<Contributor> found = new ArrayList<>();
        Set<String> classes = new HashSet<>();
        for (VidocqExtension extension : extensions) {
            if (extension instanceof StartupReportContributor contributor) {
                String className = extension.getClass().getName();
                classes.add(className);
                Contributor identified = identify(contributor, className, recorder);
                if (identified != null) {
                    found.add(identified);
                }
            }
        }
        record Ranked(Contributor contributor, int order) {}
        List<Ranked> services = new ArrayList<>();
        for (ServiceLoader.Provider<StartupReportContributor> provider : providers(loader, classes, recorder)) {
            String className = provider.type().getName();
            StartupReportContributor contributor;
            try {
                contributor = provider.get();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
                recorder.anomaly(StartupAnomalies.CONTRIBUTOR_FAILED, "Startup report contributor " + className
                        + " could not be created (" + describe(e) + "); its section is skipped", null,
                        StartupRecorder.CORE, e);
                continue;
            }
            Contributor identified = identify(contributor, className, recorder);
            if (identified == null) {
                continue;
            }
            try {
                services.add(new Ranked(identified, contributor.order()));
            } catch (RuntimeException | LinkageError e) {
                failedToGive("order", className, e, recorder);
            }
        }
        services.sort(Comparator.comparingInt(Ranked::order).thenComparing(ranked -> ranked.contributor().id()));
        for (Ranked ranked : services) {
            found.add(ranked.contributor());
        }
        return unique(found, recorder);
    }

    /**
     * The providers of the service loader, the first of each class name only, without the classes of
     * {@code known}: nothing is created here. A provider that cannot be loaded is skipped, and the next ones are
     * still looked up.
     */
    private static List<ServiceLoader.Provider<StartupReportContributor>> providers(ClassLoader loader,
                                                                                    Set<String> known,
                                                                                    StartupRecorder recorder) {
        List<ServiceLoader.Provider<StartupReportContributor>> providers = new ArrayList<>();
        Iterator<ServiceLoader.Provider<StartupReportContributor>> lookup;
        try {
            lookup = ServiceLoader.load(StartupReportContributor.class, loader).stream().iterator();
        } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
            notLoaded(e, recorder);
            return providers;
        }
        Set<String> classes = new HashSet<>(known);
        Set<String> reported = new HashSet<>();
        int failures = 0;
        while (failures < MAX_DISCOVERY_FAILURES) {
            ServiceLoader.Provider<StartupReportContributor> provider;
            try {
                if (!lookup.hasNext()) {
                    break;
                }
                provider = lookup.next();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
                failures++;
                // the same failure again is the same broken file read again: said once
                if (reported.add(e.getClass().getName() + ": " + e.getMessage())) {
                    notLoaded(e, recorder);
                }
                continue;
            }
            // a layer twin: the class of this name found first is kept, and this copy is never created
            if (classes.add(provider.type().getName())) {
                providers.add(provider);
            }
        }
        return providers;
    }

    /** {@code contributor} with its id and title, or {@code null} when it has no id it can give. */
    private static Contributor identify(StartupReportContributor contributor, String className,
                                        StartupRecorder recorder) {
        String id;
        String title;
        try {
            id = contributor.id();
        } catch (RuntimeException | LinkageError e) {
            failedToGive("id", className, e, recorder);
            return null;
        }
        if (id == null || id.isBlank()) {
            recorder.anomaly(StartupAnomalies.CONTRIBUTOR_FAILED, "Startup report contributor " + className
                    + " has no id; its section is skipped", null, StartupRecorder.CORE);
            return null;
        }
        try {
            title = contributor.title();
        } catch (RuntimeException | LinkageError e) {
            failedToGive("title", className, e, recorder);
            return null;
        }
        return new Contributor(contributor, id, title, className);
    }

    /**
     * {@code found} without the contributors whose id is taken: by a section of the core, or by a contributor of
     * another class before them ({@value StartupAnomalies#DUPLICATE_ID}), or by a twin of theirs.
     */
    private static List<Contributor> unique(List<Contributor> found, StartupRecorder recorder) {
        Map<String, String> owners = new HashMap<>();
        List<Contributor> unique = new ArrayList<>();
        for (Contributor contributor : found) {
            String id = contributor.id();
            if (CORE_IDS.contains(id)) {
                recorder.anomaly(StartupAnomalies.DUPLICATE_ID, "Contributor " + contributor.className() + " uses id '"
                        + id + "', which names a section of the core; it is skipped", null, StartupRecorder.CORE);
                continue;
            }
            String owner = owners.putIfAbsent(id, contributor.className());
            if (owner == null) {
                unique.add(contributor);
            } else if (!owner.equals(contributor.className())) {
                recorder.anomaly(StartupAnomalies.DUPLICATE_ID, "Contributors " + owner + " and "
                        + contributor.className() + " both use id '" + id + "'; " + contributor.className()
                        + " is skipped", null, StartupRecorder.CORE);
            }
        }
        return unique;
    }

    // ---------------------------------------------------------------------------------------------- calls

    /**
     * Has each contributor write its section, in order, and returns the sections of those that did not fail, with
     * those contributors. An {@link Error} other than a {@link LinkageError} goes through, the {@code recorder}
     * naming the contributor it came from.
     */
    static Contributed call(List<Contributor> contributors, ContributorContext context, StartupRecorder recorder) {
        record Written(Contributor contributor, ContributedSection section, long nanos) {}
        List<Written> written = new ArrayList<>();
        for (Contributor contributor : contributors) {
            recorder.step("contributor " + contributor.id());
            ContributedSection section = new ContributedSection(contributor.id(), recorder);
            context.writing(section);
            long started = System.nanoTime();
            try {
                contributor.instance().contribute(context, section);
                written.add(new Written(contributor, section, System.nanoTime() - started));
            } catch (RuntimeException | LinkageError e) {
                context.dropped(section);
                recorder.anomaly(StartupAnomalies.CONTRIBUTOR_FAILED, "Startup report contributor '"
                        + contributor.id() + "' (" + contributor.className()
                        + ") failed; its section is skipped", null, StartupRecorder.CORE, e);
            } finally {
                context.release();
            }
        }
        recorder.step(null);
        List<ContributedSection> kept = new ArrayList<>();
        for (Written section : written) {
            kept.add(section.section());
        }
        Map<String, String> listeners = ContributedSection.listeners(kept);
        List<Section> sections = new ArrayList<>();
        List<StartupReportContributor> instances = new ArrayList<>();
        for (Written section : written) {
            sections.add(section.section().toSection(section.contributor().title(), section.nanos(), listeners));
            instances.add(section.contributor().instance());
        }
        return new Contributed(sections, instances);
    }

    // ---------------------------------------------------------------------------------------------- anomalies

    private static void notLoaded(Throwable e, StartupRecorder recorder) {
        recorder.anomaly(StartupAnomalies.CONTRIBUTOR_FAILED, "A startup report contributor could not be loaded ("
                + describe(e) + "); its section is skipped", null, StartupRecorder.CORE, e);
    }

    private static void failedToGive(String what, String className, Throwable e, StartupRecorder recorder) {
        recorder.anomaly(StartupAnomalies.CONTRIBUTOR_FAILED, "Startup report contributor " + className
                + " failed to give its " + what + " (" + describe(e) + "); its section is skipped", null,
                StartupRecorder.CORE, e);
    }

    /** What went wrong: the message of {@code e}, else its class; the recorder cleans it with the anomaly. */
    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }
}
