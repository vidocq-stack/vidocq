package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
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
 * <p>Also exposes the default {@link io.vidocq.mansart.data.core.RepositoryRuntime} as a synthetic
 * bean (created via {@link DefaultRepositoryRuntimeCreator}) so applications get repositories
 * injectable out of the box, without having to declare any producer themselves.
 */
public final class MansartDataExtension implements BuildCompatibleExtension {

    private final List<RepoEntry> entries = new ArrayList<>();
    private final Set<String> runtimeRepoFqns = new LinkedHashSet<>();
    private boolean repositoryRuntimeAlreadyDeclared;

    record RepoEntry(String itfFqn, String implFqn) {}

    @Discovery
    public void readRepositoriesIndex(ScannedClasses scanned) {
        // RepositoryRuntime is exposed via a synthetic bean during @Synthesis (see
        // registerRepositories below) — the previous design that scanned a real
        // MansartRuntimeProducer class via ScannedClasses.add(...) caused JPMS split-package
        // errors because Vauban-processor would emit a generated MansartRuntimeProducer_Factory
        // inside the user module's output, in a package already exported by mansart-data-cdi.

        ClassLoader cl = currentClassLoader();
        Enumeration<URL> resources;
        try {
            resources = cl.getResources("META-INF/mansart-repositories.list");
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
     * Detects whether a {@link RepositoryRuntime} bean is already declared in the deployment.
     * <p>This guards against double-registration when the BCE is replayed at runtime by
     * Vauban: the {@code @Synthesis} pass at compile time already serialised a synthetic
     * {@code RepositoryRuntime} into the bean index, and the runtime pass would otherwise
     * add a second one — surfacing as
     * {@code Ambiguous dependency: parameter 0 of *RepositoryImpl() of type RepositoryRuntime}.
     * The {@code @Registration} phase runs before {@code @Synthesis} and exposes every bean
     * present in the deployment, including those serialised from a prior compile-time pass.
     */
    @Registration(types = RepositoryRuntime.class)
    public void detectExistingRepositoryRuntime(BeanInfo bean) {
        repositoryRuntimeAlreadyDeclared = true;
    }

    @Synthesis
    public void registerRepositories(SyntheticComponents components) {
        ClassLoader cl = currentClassLoader();

        // Default RepositoryRuntime bean — exposed via a synthetic bean rather than via a real
        // @Produces class in this jar. A real producer would need to be scanned through
        // ScannedClasses.add() in @Discovery, which forces Vauban-processor to emit a generated
        // *_Factory.class in the producer's package within the user module's output, creating a
        // JPMS split-package between the user module and io.vidocq.mansart.data.cdi. The
        // synthetic bean lives only in the container's bean index, never on disk.
        // Users override this default by declaring their own @Produces RepositoryRuntime method.
        // Skip when the bean is already declared (the @Registration phase saw it) — happens at
        // runtime when Vauban replays the BCE on a deployment whose compile-time @Synthesis pass
        // already serialised the same synthetic bean.
        if (!repositoryRuntimeAlreadyDeclared) {
            components.<RepositoryRuntime>addBean(RepositoryRuntime.class)
                    .type(RepositoryRuntime.class)
                    .scope(jakarta.inject.Singleton.class)
                    .createWith(DefaultRepositoryRuntimeCreator.class);
        }

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
            // Skip if the impl is already a managed CDI bean (recent mansart-data-processor
            // versions emit @Singleton on *RepositoryImpl). Re-registering as a synthetic bean
            // would create an ambiguous dependency for @Inject MyRepository.
            if (isAlreadyManagedBean(impl)) continue;
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
