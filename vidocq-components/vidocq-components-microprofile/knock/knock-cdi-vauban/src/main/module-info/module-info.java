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
 * <p><strong>Strict JPMS (module-path) requirement.</strong> Vauban's {@code BceProcessor}
 * instantiates Build Compatible Extensions by direct reflection
 * ({@code getDeclaredConstructor().newInstance()}) from module {@code io.vidocq.vauban.core}.
 * Declaring the BCE only via {@code provides ... with} is not enough on the module path: the
 * {@code provides} clause lets the {@code ServiceLoader} instantiate the class, but Vauban does
 * its own reflective instantiation, which requires the hosting package to be open to it. We
 * therefore open {@code io.vidocq.knock.cdi.internal} to {@code io.vidocq.vauban.core} — a
 * <em>qualified</em> open, so the internal package stays unexported (no API leak, honouring the
 * "internal.* not exported" constraint); only Vauban gets deep-reflective access at runtime.
 * Without it, a strict module-path deployment fails at boot with an {@code IllegalAccessException}
 * (e.g. the Vidocq runtime / Arago).</p>
 */
module io.vidocq.knock.cdi.vauban {
    requires transitive io.vidocq.knock.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.knock.cdi.internal.HealthCheckCdiExtension;

    // Qualified open so Vauban's BceProcessor can reflectively instantiate the BCE on the
    // module path, without exporting the internal package as public API.
    opens io.vidocq.knock.cdi.internal to io.vidocq.vauban.core;
}