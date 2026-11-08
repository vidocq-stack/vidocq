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
package io.vidocq.bodoni.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Bodoni's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Bodoni's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.bodoni.core} (bodoni-core, with its {@code functions},
 * {@code internal} and {@code tags.*} packages) and {@code io.vidocq.bodoni.peano} (bodoni-peano).
 */
final class ArchitectureRules {

    private static final String AGENTS_CONSTRAINTS = "AGENTS.md, Architecture Constraints Not to Violate";

    private static final String[] CORE = {"io.vidocq.bodoni.core.."};

    private static final String[] PEANO = {"io.vidocq.bodoni.peano.."};

    /**
     * Constraints 1, 6 and 7: bodoni-core depends on the JSTL API, the Pages, Servlet and EL APIs it brings, and the
     * JDK modules only: {@code java.xml} (in {@link Jdk#RUNTIME}), {@code java.sql}, {@code java.naming} and
     * {@code java.net.http} (module-info.java). No Ibarra, Peano, Foy or Vauban class, ever.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            CORE,
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.sql..",
                    "javax.naming..",
                    "jakarta.servlet..",
                    "jakarta.el..",
                    "io.vidocq.bodoni.core.."),
            AGENTS_CONSTRAINTS + ", 1 and 6; module-info.java of bodoni-core");

    /**
     * Constraint 1: the one module allowed to depend on Peano is bodoni-peano, on {@code peano-api} only, with the
     * JSTL API (module-info.java of bodoni-peano). It does not read bodoni-core.
     */
    static final ArchRule PEANO_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            PEANO,
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.servlet..",
                    "jakarta.el..",
                    "io.vidocq.peano.spi..",
                    "io.vidocq.peano.annotation..",
                    "io.vidocq.bodoni.peano.."),
            AGENTS_CONSTRAINTS + ", 1; module-info.java of bodoni-peano");

    /**
     * Constraint 1: bodoni-core and the optional bodoni-peano adapter know nothing of each other; nothing builds on
     * either.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            AGENTS_CONSTRAINTS + ", 1; the module-info.java files",
            Layer.named("Core").in(CORE).accessibleByNone(),
            Layer.named("Peano adapter").in(PEANO).accessibleByNone());

    /**
     * Constraint 1 and the ecosystem's brick/extension rule: bodoni-core runs unchanged on any Pages container, so it
     * names none of Ibarra, Peano, Foy, Vauban, nor another brick's engine.
     */
    static final ArchRule CORE_STAYS_PORTABLE = VidocqArchRules.neverDependOn(
            CORE,
            new String[] {
                "io.vidocq.ibarra..",
                "io.vidocq.peano..",
                "io.vidocq.foy..",
                "io.vidocq.vauban..",
                "io.vidocq.chappe..",
                "io.vidocq.champollion.."
            },
            AGENTS_CONSTRAINTS + ", 1; workspace AGENTS.md, a brick depends on APIs");

    /**
     * The documented reflective fallbacks, each with its reason. Nested classes share the entry of their
     * top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.of(
            "io.vidocq.bodoni.core.internal.DriverManagerDataSource",
            "Class.forName(driver, true, loader) loads the JDBC driver class a sql: data source names, the one"
                    + " reflective act the spec mandates (Tags 3.0 section 10.2, \"ensure it is loaded\"; AGENTS.md"
                    + " constraint 4; PLAN.md, Decisions recorded in B4; internals.adoc, Reflection)");

    /** Constraint 4 and workspace AGENTS.md, "Maximum static code generation". */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; " + AGENTS_CONSTRAINTS + ", 4");

    /** Workspace AGENTS.md, "Virtual Threads everywhere for I/O": c:import runs on a virtual-thread executor. */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** Constraint 5: no ThreadLocal; tag state travels in the handler instance and the PageContext. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "tag state travels in the handler instance and the PageContext: a ThreadLocal neither follows a request"
                    + " across virtual threads nor caches anything",
            AGENTS_CONSTRAINTS + ", 5; reference.adoc, Compatibility");

    private ArchitectureRules() {
        // Rules only.
    }
}
