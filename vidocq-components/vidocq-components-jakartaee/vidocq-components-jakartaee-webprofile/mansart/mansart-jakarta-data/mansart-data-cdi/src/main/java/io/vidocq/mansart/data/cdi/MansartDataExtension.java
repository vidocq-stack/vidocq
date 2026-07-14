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
package io.vidocq.mansart.data.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.data.repository.Repository;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CDI 4.1 {@link BuildCompatibleExtension} that wires Mansart {@code @Repository} interfaces into
 * the bean container. Two paths:
 *
 * <ol>
 *   <li><b>Compile-time</b> — reads {@code META-INF/mansart-repositories.list} (produced by
 *       {@code mansart-data-processor}) and registers one synthetic bean per entry, scoped
 *       {@code @Singleton}, instantiated via {@link MansartRepoCreator} from the generated
 *       {@code *RepositoryImpl} class.</li>
 *   <li><b>Runtime</b> (M7-4) — the {@code @Enhancement} phase scans for any {@code @Repository}
 *       interface that did NOT come from APT, and registers it via {@link MansartRuntimeRepoCreator}
 *       which uses {@link io.vidocq.mansart.data.core.RuntimeRepositoryProxy}. This is the path TCK
 *       deployments take, where pre-compiled jakarta.data jars do not contain APT artifacts.</li>
 * </ol>
 *
 * <p>Also makes sure {@link MansartRuntimeProducer} is scanned so the shared
 * {@link io.vidocq.mansart.data.core.RepositoryRuntime} is available for injection. Vauban-processor
 * indexes the producer class for validation but skips emitting a {@code *_Factory.class} in the
 * user module's output (Phase 1 patch on Vauban-processor) — Vauban-runtime falls back to a
 * reflective factory, avoiding the JPMS split-package that a generated class would create.
 */
public final class MansartDataExtension implements BuildCompatibleExtension {

    private final List<RepoEntry> entries = new ArrayList<>();
    private final Set<String> runtimeRepoFqns = new LinkedHashSet<>();

    record RepoEntry(String itfFqn, String implFqn) {}

    @Discovery
    public void readRepositoriesIndex(ScannedClasses scanned) {
        // Default RepositoryRuntime producer — must be in the index for its @Produces method
        // to be scanned. Vauban-processor skips emitting a *_Factory.class for it in the user
        // module (Phase 1 patch on Vauban-processor recognises classes added via
        // ScannedClasses.add(...) as external) — Vauban-runtime falls back to a reflective
        // factory at boot, so no on-disk artefact lives in the user module.
        scanned.add(MansartRuntimeProducer.class.getName());

        ClassLoader cl = currentClassLoader();
        // Two compile-time indices, identical format (itf=impl): the APT-produced list (repositories
        // in the application's own sources) and the mansart-data-maven-plugin list (repositories from
        // pre-compiled dependency jars, generated ahead of time into an app-owned package). Both map
        // to a @Singleton *Impl with an (RepositoryRuntime) constructor → MansartRepoCreator path.
        for (String resource : new String[]{
                "META-INF/mansart-repositories.list",
                "META-INF/mansart-repositories-external.list"}) {
            Enumeration<URL> resources;
            try {
                resources = cl.getResources(resource);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int eq = line.indexOf('=');
                        if (eq < 0) continue;
                        entries.add(new RepoEntry(line.substring(0, eq).trim(), line.substring(eq + 1).trim()));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /**
     * M7-4 — auto-discover {@code @Repository} interfaces that were not registered through the
     * compile-time META-INF list. Such interfaces are routed to the runtime Proxy path.
     *
     * <p>Uses the wildcard {@code types = Object.class} + {@code withSubtypes = true} which is the
     * BCE idiom for "every class in the bean archive" — interfaces are then filtered by the body
     * of the method via {@link ClassInfo#isInterface()} and the annotation predicate.
     */
    @Enhancement(types = Object.class, withSubtypes = true)
    public void discoverRuntimeRepositories(ClassInfo info) {
        if (!info.isInterface()) return;
        // Use the typed hasAnnotation(Class) — implementations are required to resolve this
        // as a name match against the index, without forcing the annotation type itself to
        // be loadable through the bean archive index (unlike iterating annotations()).
        if (!info.hasAnnotation(Repository.class)) return;
        String fqn = info.name();
        for (RepoEntry e : entries) {
            if (e.itfFqn().equals(fqn)) return;     // already covered by compile-time entry
        }
        runtimeRepoFqns.add(fqn);
    }

    /**
     * MANSART-005 — a bean class implementing an interface that declares a non-default
     * {@code dataStore} must NOT become a managed bean: the APT-generated constructor injects the
     * unqualified ({@code @Default}) {@link io.vidocq.mansart.data.core.RepositoryRuntime}, which
     * would silently route every operation to the default datasource. Veto it here so the
     * {@code @Synthesis} phase registers the synthetic bean instead, whose creator resolves the
     * runtime through {@link DataStoreResolver} (CDI {@code @Named} / JNDI). Same
     * {@code @Enhancement + @Vetoed} idiom as Ravel's {@code ConfigCdiExtension} (portable
     * extensions never run on CDI Lite).
     *
     * <p>Deliberately stateless (no {@code entries} lookup): the container may run
     * {@code @Enhancement} on a fresh extension instance (Vauban's archive-class pass does).
     * The interface is found by reflection on the loaded class, not through the lang model —
     * index-backed {@code ClassInfo.superInterfacesDeclarations()} is empty for pre-indexed
     * application classes on the module path. Class-loading tolerance matches
     * {@code @Synthesis}: when the class is not loadable (APT-time replay), no veto happens
     * and no synthetic bean is registered either, keeping both sides consistent.
     */
    @Enhancement(types = Object.class, withSubtypes = true)
    public void vetoRoutedRepositoryImpls(jakarta.enterprise.inject.build.compatible.spi.ClassConfig clazz) {
        if (clazz.info().isInterface()) return;
        Class<?> impl;
        try {
            impl = currentClassLoader().loadClass(clazz.info().name());
        } catch (ClassNotFoundException | LinkageError ex) {
            return;
        }
        for (Class<?> itf : impl.getInterfaces()) {
            Repository ann = itf.getAnnotation(Repository.class);
            if (ann == null) continue;
            if (isRouted(ann.dataStore())) {
                clazz.addAnnotation(jakarta.enterprise.inject.Vetoed.class);
            }
            return;
        }
    }

    @Synthesis
    public void registerRepositories(SyntheticComponents components) {
        ClassLoader cl = currentClassLoader();

        // Compile-time path — APT-generated *RepositoryImpl from mansart-data-processor
        for (RepoEntry e : entries) {
            Class<?> itf;
            Class<?> impl;
            try {
                itf  = cl.loadClass(e.itfFqn());
                impl = cl.loadClass(e.implFqn());
            } catch (ClassNotFoundException ex) {
                // Compile-time: the impl/interface aren't loadable yet from the APT classloader.
                // Skip silently — the runtime container will register the bean correctly when it
                // re-runs this BCE with a classloader that can see the user module.
                continue;
            }
            Repository ann = itf.getAnnotation(Repository.class);
            boolean routed = ann != null && isRouted(ann.dataStore());
            // Default dataStore: skip if the impl is already a managed CDI bean (recent
            // mansart-data-processor versions emit @Singleton on *RepositoryImpl) — its
            // unqualified RepositoryRuntime injection is correct there, and re-registering a
            // synthetic bean would create an ambiguous dependency for @Inject MyRepository.
            // Non-default dataStore (MANSART-005): the impl was vetoed at @Enhancement, so the
            // synthetic bean below is the only one and its creator routes via DataStoreResolver.
            if (!routed && isAlreadyManagedBean(impl)) continue;
            registerCompileTime(components, itf, impl);
        }

        // Runtime path (M7-4) — @Repository interfaces with no APT-generated impl (e.g. TCK).
        for (String fqn : runtimeRepoFqns) {
            Class<?> itf;
            try {
                itf = cl.loadClass(fqn);
            } catch (ClassNotFoundException ex) {
                // Same compile-time tolerance as above: defer to runtime.
                continue;
            }
            // Skip if a sibling *RepositoryImpl bean already covers this interface (handles the
            // case where the runtime path saw a @Repository whose impl is actually APT-generated
            // and managed by CDI — happens when the bean archive contains both).
            if (hasManagedImplFor(cl, fqn)) continue;
            registerRuntime(components, itf);
        }
    }

    private static boolean isAlreadyManagedBean(Class<?> impl) {
        return impl.isAnnotationPresent(jakarta.inject.Singleton.class)
                || impl.isAnnotationPresent(jakarta.enterprise.context.ApplicationScoped.class)
                || impl.isAnnotationPresent(jakarta.enterprise.context.Dependent.class);
    }

    /** True when {@code dataStore} names a specific store (CDI {@code @Named} or JNDI). */
    private static boolean isRouted(String dataStore) {
        return dataStore != null && !dataStore.isEmpty()
                && !Repository.DEFAULT_DATA_STORE.equals(dataStore);
    }

    private static boolean hasManagedImplFor(ClassLoader cl, String itfFqn) {
        // Convention: APT-generated implementation lives next to the interface as <Itf>Impl.
        // We accept multi-package layouts only via the explicit compile-time list above; this
        // heuristic is the runtime-path twin and only checks the conventional sibling name.
        try {
            Class<?> impl = cl.loadClass(itfFqn + "Impl");
            return isAlreadyManagedBean(impl);
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerCompileTime(SyntheticComponents components, Class<?> itf, Class<?> impl) {
        // M9 — itfClass is forwarded so the creator can read @Repository(dataStore) and pick
        // the matching RepositoryRuntime (CDI @Named or JNDI lookup).
        components.<Object>addBean((Class) itf)
                .type(itf)
                .scope(jakarta.inject.Singleton.class)
                .withParam("implClass", impl)
                .withParam("itfClass", itf)
                .createWith(MansartRepoCreator.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerRuntime(SyntheticComponents components, Class<?> itf) {
        components.<Object>addBean((Class) itf)
                .type(itf)
                .scope(jakarta.inject.Singleton.class)
                .withParam("itfClass", itf)
                .createWith(MansartRuntimeRepoCreator.class);
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : MansartDataExtension.class.getClassLoader();
    }
}
