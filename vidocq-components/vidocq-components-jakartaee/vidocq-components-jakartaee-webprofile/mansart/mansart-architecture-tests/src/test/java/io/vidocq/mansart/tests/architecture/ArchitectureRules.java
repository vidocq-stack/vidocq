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
package io.vidocq.mansart.tests.architecture;

import java.util.Map;
import java.util.stream.Stream;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Mansart's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Mansart's packages; each names the document
 * it comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.mansart.pool} (mansart-pool-api, this package alone)
 * and {@code io.vidocq.mansart.pool.core} (mansart-pool-core); {@code io.vidocq.mansart.transactions.api},
 * {@code .core}, {@code .jdbc} and {@code .cdi} (mansart-transactions-*); {@code io.vidocq.mansart.data.dialect}
 * and {@code .dialect.attribute} (mansart-data-dialect-spi), {@code .dialect.h2} and {@code .dialect.postgresql}
 * (the dialects), {@code io.vidocq.mansart.data.core}, {@code .processor} and {@code .cdi} (mansart-data-*). The
 * Maven plugin runs on the build machine and is not covered.
 *
 * <p>Beyond {@link Jdk#RUNTIME}, the modules read {@code javax.sql} (module {@code java.sql}, JDBC is in the JDK,
 * CLAUDE.md constraint 3), {@code javax.naming} ({@code java.naming}) and {@code javax.transaction.xa}
 * ({@code java.transaction.xa}), listed where they are used. A {@code javax} package outside the JDK is not one.
 */
final class ArchitectureRules {

    private static final String CLAUDE_CONSTRAINTS = "CLAUDE.md, Architecture Constraints";

    private static final String[] POOL_API = {"io.vidocq.mansart.pool"};

    private static final String[] POOL_CORE = {"io.vidocq.mansart.pool.core.."};

    private static final String[] TX_API = {"io.vidocq.mansart.transactions.api.."};

    private static final String[] TX_CORE = {"io.vidocq.mansart.transactions.core.."};

    private static final String[] TX_JDBC = {"io.vidocq.mansart.transactions.jdbc.."};

    private static final String[] TX_CDI = {"io.vidocq.mansart.transactions.cdi.."};

    private static final String[] DIALECT_SPI = {
        "io.vidocq.mansart.data.dialect", "io.vidocq.mansart.data.dialect.attribute"
    };

    private static final String[] DIALECTS = {
        "io.vidocq.mansart.data.dialect.h2..", "io.vidocq.mansart.data.dialect.postgresql.."
    };

    private static final String[] DATA_CORE = {"io.vidocq.mansart.data.core.."};

    private static final String[] DATA_PROCESSOR = {"io.vidocq.mansart.data.processor.."};

    private static final String[] DATA_CDI = {"io.vidocq.mansart.data.cdi.."};

    /** mansart-pool-api: a JDBC {@code DataSource} value type, nothing else (module-info.java). */
    static final ArchRule POOL_API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            POOL_API,
            Packages.of(Jdk.RUNTIME, "javax.sql..", "io.vidocq.mansart.pool"),
            CLAUDE_CONSTRAINTS + ", constraint 3, zero dependency; module-info.java of mansart-pool-api");

    /**
     * mansart-pool-core: the JDK and mansart-pool-api; the pool "only provides a javax.sql.DataSource". The JDK's
     * XA package is the return type of {@code javax.sql.XAConnection.getXAResource()}, which the pool's XA
     * connections implement (MANSART-065); {@code java.sql} requires its module transitively.
     */
    static final ArchRule POOL_CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            POOL_CORE,
            Packages.of(Jdk.RUNTIME, "javax.sql..", "javax.transaction.xa..", "io.vidocq.mansart.pool",
                    "io.vidocq.mansart.pool.core.."),
            CLAUDE_CONSTRAINTS + ", constraint 3, zero dependency; module-info.java of mansart-pool-core");

    /** mansart-transactions-api: the Jakarta Transactions, CDI and Interceptors APIs (module-info.java). */
    static final ArchRule TRANSACTIONS_API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            TX_API,
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.transaction..",
                    "jakarta.enterprise..",
                    "jakarta.interceptor..",
                    "io.vidocq.mansart.transactions.api.."),
            CLAUDE_CONSTRAINTS + ", constraint 3; module-info.java of mansart-transactions-api");

    /** mansart-transactions-core: the Transactions API and the JDK's XA package, on mansart-transactions-api. */
    static final ArchRule TRANSACTIONS_CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            TX_CORE,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.transaction.xa..",
                    "jakarta.transaction..",
                    "io.vidocq.mansart.transactions.api..",
                    "io.vidocq.mansart.transactions.core.."),
            CLAUDE_CONSTRAINTS + ", constraint 3; module-info.java of mansart-transactions-core");

    /**
     * mansart-transactions-jdbc: JDBC and XA on mansart-transactions-core (module-info.java), and the
     * Transactions API, whose current transaction {@code JtaConnections} enlists its connection in (MANSART-066).
     */
    static final ArchRule TRANSACTIONS_JDBC_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            TX_JDBC,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.sql..",
                    "javax.transaction.xa..",
                    "jakarta.transaction..",
                    "io.vidocq.mansart.transactions.core..",
                    "io.vidocq.mansart.transactions.jdbc.."),
            CLAUDE_CONSTRAINTS + ", constraint 3; module-info.java of mansart-transactions-jdbc");

    /**
     * mansart-transactions-cdi: the CDI, Interceptors, Transactions, Inject and Annotation APIs, mansart-transactions-core,
     * and Vauban's API alone, a compile-only {@code requires static} (module-info.java): the generated
     * {@code _VaubanComponents} provider. Never Vauban's container.
     */
    static final ArchRule TRANSACTIONS_CDI_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            TX_CDI,
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.enterprise..",
                    "jakarta.interceptor..",
                    "jakarta.transaction..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.mansart.transactions.api..",
                    "io.vidocq.mansart.transactions.core..",
                    "io.vidocq.mansart.transactions.cdi.."),
            CLAUDE_CONSTRAINTS + ", constraint 3; module-info.java of mansart-transactions-cdi");

    /** mansart-data-dialect-spi: the SQL neutral AST, on JDBC and the Jakarta Data API (module-info.java). */
    static final ArchRule DIALECT_SPI_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            DIALECT_SPI,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.sql..",
                    "jakarta.data..",
                    "io.vidocq.mansart.data.dialect",
                    "io.vidocq.mansart.data.dialect.attribute"),
            CLAUDE_CONSTRAINTS + ", constraint 4; module-info.java of mansart-data-dialect-spi");

    /** The two dialects: the SPI, and mansart-data-core for its exception (module-info.java). */
    static final ArchRule DIALECT_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            DIALECTS,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.sql..",
                    "jakarta.data..",
                    "io.vidocq.mansart.data.dialect",
                    "io.vidocq.mansart.data.dialect.attribute",
                    "io.vidocq.mansart.data.core..",
                    "io.vidocq.mansart.data.dialect.h2..",
                    "io.vidocq.mansart.data.dialect.postgresql.."),
            CLAUDE_CONSTRAINTS + ", constraint 4; module-info.java of the dialect modules");

    /** mansart-data-core: the Jakarta Data and Inject APIs, JDBC and the dialect SPI (module-info.java). */
    static final ArchRule DATA_CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            DATA_CORE,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.sql..",
                    "jakarta.data..",
                    "jakarta.inject..",
                    "io.vidocq.mansart.data.dialect",
                    "io.vidocq.mansart.data.dialect.attribute",
                    "io.vidocq.mansart.data.core.."),
            CLAUDE_CONSTRAINTS + ", constraint 3; module-info.java of mansart-data-core");

    /** mansart-data-processor: a build-time tool, {@code java.compiler} and the data modules (module-info.java). */
    static final ArchRule DATA_PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            DATA_PROCESSOR,
            Packages.of(
                    Jdk.COMPILER,
                    "io.vidocq.mansart.data.dialect",
                    "io.vidocq.mansart.data.dialect.attribute",
                    "io.vidocq.mansart.data.core..",
                    "io.vidocq.mansart.data.processor.."),
            CLAUDE_CONSTRAINTS + ", constraint 1; module-info.java of mansart-data-processor");

    /**
     * mansart-data-cdi: the CDI, Inject, Data, JDBC and JNDI APIs, Vauban's API alone (the generated
     * {@code _VaubanComponents} provider, never Vauban's container), and the transaction bridge, a
     * {@code requires static} on the Transactions API and mansart-transactions-jdbc (module-info.java).
     */
    static final ArchRule DATA_CDI_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            DATA_CDI,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.sql..",
                    "javax.naming..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.data..",
                    "jakarta.transaction..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.mansart.transactions.jdbc..",
                    "io.vidocq.mansart.data.dialect",
                    "io.vidocq.mansart.data.dialect.attribute",
                    "io.vidocq.mansart.data.core..",
                    "io.vidocq.mansart.data.cdi.."),
            CLAUDE_CONSTRAINTS + ", constraint 3; module-info.java of mansart-data-cdi");

    /**
     * README and CLAUDE.md, Mission: three building blocks "independent at runtime", and the module descriptors.
     * Each *-api is the bottom of its block and reads nothing else; the core builds on it; the adapters
     * (transactions-cdi, the dialects, the processor, data-cdi) are reached by nothing. The one bridge between
     * blocks is mansart-data-cdi, which reads mansart-transactions-jdbc, statically, to enlist in a JTA
     * transaction.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            CLAUDE_CONSTRAINTS + "; CLAUDE.md, Mission; module-info.java files",
            Layer.named("Pool API").in(POOL_API).accessesNoLayer().accessibleOnlyBy("Pool core"),
            Layer.named("Pool core").in(POOL_CORE).accessibleByNone(),
            Layer.named("Transactions API")
                    .in(TX_API)
                    .accessesNoLayer()
                    .accessibleOnlyBy("Transactions core", "Transactions JDBC", "Transactions CDI"),
            Layer.named("Transactions core")
                    .in(TX_CORE)
                    .accessibleOnlyBy("Transactions JDBC", "Transactions CDI", "Data CDI"),
            Layer.named("Transactions JDBC").in(TX_JDBC).accessibleOnlyBy("Data CDI"),
            Layer.named("Transactions CDI").in(TX_CDI).accessibleByNone(),
            Layer.named("Dialect SPI")
                    .in(DIALECT_SPI)
                    .accessibleOnlyBy("Dialects", "Data core", "Data processor", "Data CDI"),
            Layer.named("Dialects").in(DIALECTS).accessibleByNone(),
            Layer.named("Data core").in(DATA_CORE).accessibleOnlyBy("Dialects", "Data processor", "Data CDI"),
            Layer.named("Data processor").in(DATA_PROCESSOR).accessibleByNone(),
            Layer.named("Data CDI").in(DATA_CDI).accessibleByNone());

    /**
     * Workspace AGENTS.md, dependency graph and layering: a {@code *-api} module never depends on a
     * {@code *-core} one.
     */
    static final ArchRule API_NEVER_ON_CORE = VidocqArchRules.neverDependOn(
            join(POOL_API, TX_API),
            join(POOL_CORE, TX_CORE, TX_JDBC, TX_CDI, DATA_CORE, DATA_CDI),
            "workspace AGENTS.md, dependency graph; module-info.java of the *-api modules");

    /**
     * CLAUDE.md, Mission: "mansart-pool is completely decoupled: it only provides a javax.sql.DataSource usable by
     * any JDBC client". Nothing of the pool reads another block.
     */
    static final ArchRule POOL_STANDS_ALONE = VidocqArchRules.neverDependOn(
            join(POOL_API, POOL_CORE),
            join(TX_API, TX_CORE, TX_JDBC, TX_CDI, DIALECT_SPI, DIALECTS, DATA_CORE, DATA_PROCESSOR, DATA_CDI),
            CLAUDE_CONSTRAINTS + "; CLAUDE.md, Mission");

    /**
     * Workspace AGENTS.md, dependency graph: Mansart reads the foundational bricks through their APIs, never their
     * implementations: no Chappe, no Champollion, and from Vauban only {@code vauban-api}.
     */
    static final ArchRule FOUNDATIONAL_BRICKS = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.mansart.."},
            new String[] {
                "io.vidocq.chappe..", "io.vidocq.champollion..", "io.vidocq.vauban.core..", "io.vidocq.vauban.webcontexts.."
            },
            "workspace AGENTS.md, dependency graph");

    private static final String RUNTIME_PATH = "the M7 runtime path, the reflective fallback for a @Repository"
            + " interface with no compile-time implementation (mansart-jakarta-data.adoc, Functional scope and"
            + " Repositories from external jars; Javadoc of RuntimeRepositoryProxy)";

    /**
     * The documented reflective fallbacks, each with its reason. Nested classes share the entry of their
     * top-level class. The first group is the runtime path of mansart-data-core and the lookups of the code
     * the processor generates; the others read a thing that is only known by name or only handed over as a
     * {@code Method} or a {@code Class}. The shipped classes outside this list use no reflection.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry("io.vidocq.mansart.data.core.RuntimeRepositoryProxy", RUNTIME_PATH),
            Map.entry("io.vidocq.mansart.data.core.RuntimeRepositoryClassGenerator", RUNTIME_PATH
                    + ": it reads the abstract methods of the interface to emit one stub each"),
            Map.entry("io.vidocq.mansart.data.core.RuntimeEntityModelBuilder", RUNTIME_PATH
                    + ": it reads the JPA mapping annotations of an entity that has no generated _Entity metamodel"),
            Map.entry("io.vidocq.mansart.data.core.RuntimeGeneration", RUNTIME_PATH
                    + ": it warns once per repository served that way, and checks the modifiers of the interface to"
                    + " tell why the generator could not implement it (internals.adoc, the framed WARNING)"),
            Map.entry("io.vidocq.mansart.data.core.JdqlExecutor", RUNTIME_PATH
                    + ": it runs the @Query of a runtime-path repository and reads the return type, the @Param"
                    + " names and the record components of the method"),
            Map.entry("io.vidocq.mansart.data.core.EntityModels", "looks the compile-time metamodel up by name"
                    + " (<pkg>._<Entity>.$MODEL) before falling back to RuntimeEntityModelBuilder (Javadoc of"
                    + " EntityModels; reference.adoc, mansart-data-core)"),
            Map.entry("io.vidocq.mansart.data.core.MansartData", "the standalone bootstrap loads the generated"
                    + " <Repository>Impl by name and calls its constructor: outside CDI nothing else can find the"
                    + " class the processor generated (Javadoc of MansartData)"),
            Map.entry("io.vidocq.mansart.data.cdi.MansartRepoCreator", "the synthetic bean creator calls the"
                    + " constructor of the generated <Repository>Impl, a Class that build-time parameters carry"
                    + " (Javadoc of MansartRepoCreator)"),
            Map.entry("io.vidocq.mansart.data.cdi.MansartDataExtension", "reads @Repository, and the scope"
                    + " annotations of a generated class, on the loaded class: the build-compatible extension"
                    + " lang model is empty for pre-indexed application classes (Javadoc of"
                    + " vetoRoutedRepositoryImpls)"),
            Map.entry("io.vidocq.mansart.data.cdi.DataStoreResolver", "reads @Repository(dataStore) of the"
                    + " interface a synthetic bean serves, to route it to a named data store (M9)"),
            Map.entry("io.vidocq.mansart.data.cdi.JtaBridgeActivator", "probes by name whether the Transactions API"
                    + " and mansart-transactions-jdbc are present, both being requires static, so that a"
                    + " deployment without transactions keeps working (module-info.java of mansart-data-cdi)"),
            Map.entry("io.vidocq.mansart.pool.core.MansartDataSource", "builds the XADataSource that"
                    + " PoolConfig.xaDataSourceClassName names, a class known only by name from the configuration,"
                    + " and sets its URL, user, password and driver properties through the vendor's bean setters (reference.adoc,"
                    + " PoolConfig)"),
            Map.entry("io.vidocq.mansart.transactions.cdi.TransactionalInterceptor", "Jakarta Interceptors hands"
                    + " the intercepted member over as a java.lang.reflect.Method: the interceptor reads its"
                    + " @Transactional, then that of the target class hierarchy"));

    /** Workspace AGENTS.md, "Maximum static code generation"; CLAUDE.md, constraints 1 and 2. */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; CLAUDE.md, constraints 1 and 2");

    /** Workspace AGENTS.md, "Virtual Threads everywhere for I/O"; CLAUDE.md, constraint 5. */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.UNLESS_THREAD_FACTORY_GIVEN,
            "workspace AGENTS.md, Virtual Threads; CLAUDE.md, constraint 5");

    /** The documented holders of a ThreadLocal, each with its reason. */
    static final Map<String, String> THREAD_LOCAL_HOLDERS = Map.of(
            "io.vidocq.mansart.transactions.core.MansartTransactionManager",
            "the transactional context is a per-thread ThreadLocal, a deliberate choice: the Jakarta Transactions API"
                    + " is imperative (begin() returns, commit() comes later from arbitrary call sites), which a"
                    + " ScopedValue cannot express (concepts.adoc; modules/mansart-transactions.adoc, architecture"
                    + " note)");

    /**
     * Workspace AGENTS.md, "Virtual Threads" and the brick's reference page: no ThreadLocal, except the documented
     * transaction context ({@link #THREAD_LOCAL_HOLDERS}), which the test leaves out of the classes this rule
     * is given.
     */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows a task across virtual threads nor caches anything; the transactional"
                    + " context is the one documented exception",
            "workspace AGENTS.md, Virtual Threads; concepts.adoc");

    private ArchitectureRules() {
        // Rules only.
    }

    private static String[] join(String[]... packages) {
        return Stream.of(packages).flatMap(Stream::of).toArray(String[]::new);
    }
}
