/**
 * Intégration CDI de Knock pour le container Vauban — découverte automatique des beans
 * annotés {@code @Liveness}, {@code @Readiness}, {@code @Startup} via Build Compatible
 * Extension, enregistrement dans le {@code HealthCheckRegistry}.
 *
 * <p>Module optionnel : un déploiement standalone SE n'a pas besoin de ce module
 * et peut alimenter le registry directement via son API programmatique.</p>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong>
 * (voir {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :
 * {@code module-info.java} est dans {@code src/main/module-info/} pour éviter que
 * Maven Compiler Plugin détecte JPMS lors de {@code testCompile} (vauban-core est
 * test-scope, absent de {@code target/javamodules/}).</p>
 *
 * <p>Pour un déploiement JPMS strict en production, ajouter
 * {@code opens io.vidocq.knock.cdi.internal to io.vidocq.vauban.core} afin que
 * Vauban puisse instancier les beans CDI internes (reporté à M4/M5).</p>
 */
module io.vidocq.knock.cdi.vauban {
    requires transitive io.vidocq.knock.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.knock.cdi.internal.HealthCheckCdiExtension;
}