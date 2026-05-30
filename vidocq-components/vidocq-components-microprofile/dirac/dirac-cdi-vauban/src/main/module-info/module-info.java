/**
 * CDI integration of Dirac for the Vauban container.
 *
 * <p>Planned components (see ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code CountedInterceptor} — CDI {@code @Interceptor} with priority 4020
 *       for {@code @Counted}.</li>
 *   <li>{@code TimedInterceptor} — CDI {@code @Interceptor} with priority 4021
 *       for {@code @Timed}.</li>
 *   <li>{@code DiracExtension} — Vauban BCE {@code BuildCompatibleExtension}: resolves
 *       {@code @Gauge} at startup via {@code MethodHandle}, validates signatures,
 *       populates the BASE registry (JVM metrics).</li>
 *   <li>{@code MetricRegistryProducerBean} — produces the three {@code MetricRegistry}
 *       ({@code APPLICATION}, {@code BASE}, {@code VENDOR}) as {@code @ApplicationScoped}
 *       beans with the {@code @RegistryScope} qualifier.</li>
 * </ul>
 *
 * <p><strong>JPMS note — testCompile workaround</strong> :
 * {@code module-info.java} is in {@code src/main/module-info/} to prevent Maven
 * Compiler Plugin from detecting JPMS during {@code testCompile} (vauban-core is test-scope,
 * absent from {@code target/javamodules/}).
 * See {@code dirac-core/pom.xml} for the full workaround description.</p>
 */
module io.vidocq.dirac.cdi.vauban {
    requires transitive io.vidocq.dirac.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    requires static jakarta.interceptor;

    exports io.vidocq.dirac.cdi.internal;

    // Dirac BCE: @Gauge resolution + BASE registry population at startup
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.dirac.cdi.internal.DiracExtension;
}
