/**
 * Knock CDI integration for the Vauban container — automatic discovery of beans
 * annotated with {@code @Liveness}, {@code @Readiness}, and {@code @Startup} via a
 * Build Compatible Extension, with registration in the {@code HealthCheckRegistry}.
 *
 * <p>Optional module: a standalone SE deployment does not need this module and can feed
 * the registry directly through its programmatic API.</p>
 *
 * <p><strong>JPMS note — testCompile workaround</strong>
 * (see {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :
 * {@code module-info.java} is in {@code src/main/module-info/} to prevent Maven Compiler
 * Plugin from detecting JPMS during {@code testCompile} (vauban-core is test-scope,
 * absent from {@code target/javamodules/}).</p>
 *
 * <p>For a strict JPMS production deployment, add
 * {@code opens io.vidocq.knock.cdi.internal to io.vidocq.vauban.core} so that Vauban
 * can instantiate the internal CDI beans (deferred to M4/M5).</p>
 */
module io.vidocq.knock.cdi.vauban {
    requires transitive io.vidocq.knock.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.knock.cdi.internal.HealthCheckCdiExtension;
}