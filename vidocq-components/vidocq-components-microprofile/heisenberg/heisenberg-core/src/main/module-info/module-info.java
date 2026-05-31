/**
 * Pure Java 25 Fault Tolerance policy engines — no CDI dependency.
 *
 * <p>Planned components (cf. ROADMAP.md M1-M7) :</p>
 * <ul>
 *   <li>{@code RetryEngine} — retry state machine with delay, jitter, maxDuration.</li>
 *   <li>{@code TimeoutEngine} — timeout via {@code Thread.ofVirtual() + join(Duration)} (Java 21+, finalized).</li>
 *   <li>{@code CircuitBreakerEngine} — CLOSED/OPEN/HALF_OPEN circuit breaker, sliding window.</li>
 *   <li>{@code BulkheadEngine} — isolation via {@code Semaphore} (sync) or queue (async).</li>
 *   <li>{@code FallbackResolver} — resolves {@code FallbackHandler} or {@code fallbackMethod}
 *       via {@code MethodHandle}.</li>
 *   <li>{@code PolicyComposer} — policy chain in spec §2.5 order.</li>
 *   <li>{@code AnnotationReader} — reads FT annotations on method, then class.</li>
 *   <li>{@code ConfigResolver} — MP Config §9 precedence (method > class > global).</li>
 * </ul>
 *
 * <p><strong>JPMS note — testCompile workaround</strong>:
 * This {@code module-info.java} is in {@code src/main/module-info/} (not
 * {@code src/main/java/}) so that Maven Compiler Plugin does not detect JPMS during
 * {@code testCompile}. {@code maven-clean-plugin} deletes {@code module-info.class} before
 * {@code testCompile} (incremental builds). A {@code prepare-package}
 * execution recompiles only {@code module-info.java}. The tests run on the classpath
 * ({@code useModulePath=false}) — JPMS wiring is validated by the TCK smoke test.</p>
 */
module io.vidocq.heisenberg.core {
    requires transitive io.vidocq.heisenberg.api;

    // Configuration externe via MicroProfile Config (Ravel en runtime)
    requires org.eclipse.microprofile.config;

    exports io.vidocq.heisenberg.internal to io.vidocq.heisenberg.cdi.vauban;
}
