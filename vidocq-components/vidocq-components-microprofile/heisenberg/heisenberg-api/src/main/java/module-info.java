/**
 * Heisenberg API: controlled re-export of the MicroProfile Fault Tolerance 4.1 spec
 * and stable public SPI of the Vidocq implementation.
 *
 * <p><strong>JPMS note — possible automatic module without {@code Automatic-Module-Name}</strong>:
 * If {@code microprofile-fault-tolerance-api:4.1} has neither {@code Automatic-Module-Name} in its
 * {@code MANIFEST.MF} nor a {@code module-info.class}, the JPMS name used is
 * {@code microprofile.fault.tolerance.api} (derived by Java from the Maven artifact name:
 * strip version + replace {@code -} with {@code .}).
 * The parent POM forces this JAR onto the module path via {@code target/javamodules/}
 * (see {@code maven-dependency-plugin} in the {@code initialize} phase).</p>
 *
 * <p>Planned contents (cf. ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>Transitive re-export of the {@code @Retry}, {@code @Timeout},
 *       {@code @CircuitBreaker}, {@code @Bulkhead}, {@code @Fallback}, {@code @Asynchronous} annotations.</li>
 *   <li>{@code PolicyContext} — enriched invocation context exposed to the core engines.</li>
 *   <li>Immutable configs: {@code RetryConfig}, {@code TimeoutConfig},
 *       {@code CircuitBreakerConfig}, {@code BulkheadConfig} (records).</li>
 * </ul>
 */
module io.vidocq.heisenberg.api {
    requires transitive microprofile.fault.tolerance.api;

    exports io.vidocq.heisenberg.api;
}
