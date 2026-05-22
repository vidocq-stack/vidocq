/**
 * Intégration CDI de Dirac pour le container Vauban.
 *
 * <p>Composants prévus (cf. ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code CountedInterceptor} — intercepteur CDI {@code @Interceptor} de priorité 4020
 *       pour {@code @Counted}.</li>
 *   <li>{@code TimedInterceptor} — intercepteur CDI {@code @Interceptor} de priorité 4021
 *       pour {@code @Timed}.</li>
 *   <li>{@code DiracExtension} — BCE Vauban {@code BuildCompatibleExtension} : résout les
 *       {@code @Gauge} au démarrage via {@code MethodHandle}, valide les signatures,
 *       peuple le registre BASE (métriques JVM).</li>
 *   <li>{@code MetricRegistryProducerBean} — produit les trois {@code MetricRegistry}
 *       ({@code APPLICATION}, {@code BASE}, {@code VENDOR}) comme beans {@code @ApplicationScoped}
 *       avec qualifier {@code @RegistryScope}.</li>
 * </ul>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong> :
 * {@code module-info.java} est dans {@code src/main/module-info/} pour éviter que Maven
 * Compiler Plugin détecte JPMS lors de {@code testCompile} (vauban-core est test-scope,
 * absent de {@code target/javamodules/}).
 * Voir {@code dirac-core/pom.xml} pour la description complète du workaround.</p>
 */
module io.vidocq.dirac.cdi.vauban {
    requires transitive io.vidocq.dirac.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    requires static jakarta.interceptor;

    exports io.vidocq.dirac.cdi.internal;

    // BCE Dirac : résolution @Gauge + peuplement registre BASE au démarrage
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.dirac.cdi.internal.DiracExtension;
}
