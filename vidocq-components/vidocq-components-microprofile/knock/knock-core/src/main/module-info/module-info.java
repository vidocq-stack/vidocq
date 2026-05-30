/**
 * Standalone MicroProfile Health 4.0 implementation — usable in plain SE,
 * without CDI or a container.
 *
 * <p>JSON serialization is produced via Jakarta JSON-P (jakarta.json);
 * champollion is the reference implementation provided at runtime.</p>
 *
 * <p><strong>JPMS note — Vidocq fork of the MicroProfile Health API</strong>
 * (see {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :</p>
 *
 * <ol>
 *   <li><em>Double SPI registration.</em> {@code HealthCheckResponse.named()}
 *   invokes {@code ServiceLoader} through the {@code ContextClassLoader}.
 *   {@code KnockHealthCheckResponseProvider} is therefore registered <em>twice</em>:
 *   {@code provides ... with} here (JPMS ServiceLoader) and in
 *   {@code META-INF/services/} (ClassLoader ServiceLoader — mandatory because this is the
 *   mechanism used by {@code HealthCheckResponse}).</li>
 *
 *   <li><em>Three-step Maven build.</em> {@code module-info.java} is placed in
 *   {@code src/main/module-info/} (not {@code src/main/java/}) so that Maven Compiler
 *   Plugin does not detect JPMS during {@code testCompile}. {@code maven-clean-plugin}
 *   purges {@code module-info.class} before {@code testCompile} (incremental builds).
 *   A {@code prepare-package} run recompiles only {@code module-info.java} before
 *   assembling the JAR. Tests run on the classpath ({@code useModulePath=false}).</li>
 * </ol>
 *
 * <p>The fork {@code io.vidocq.knock:knock-mp-health-api} includes a
 * {@code module-info.class} (module {@code microprofile.health.api}), which makes Knock
 * {@code jlink}-compatible without automatic modules.</p>
 */
module io.vidocq.knock.core {
    requires transitive io.vidocq.knock.api;

    // JSON-P for health response serialization (spec §3.1)
    requires jakarta.json;

    // Exported runtime SPI — stable entry point for adapters
    // (knock-cdi-vauban, knock-cassini). The io.vidocq.knock.internal package
    // remains intentionally unexported (project JPMS boundary).
    exports io.vidocq.knock.runtime;

    // HealthCheckResponseProvider SPI — declared for strict JPMS environments
    // (the provider is also declared in META-INF/services for the ClassLoader fallback)
    provides org.eclipse.microprofile.health.spi.HealthCheckResponseProvider
            with io.vidocq.knock.internal.KnockHealthCheckResponseProvider;
}
