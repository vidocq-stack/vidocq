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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** The classes the architecture rules are applied to, imported once per test run. */
final class MansartClasses {

    /**
     * The root package of each family of shipped modules: mansart-pool-api and -core, the four
     * mansart-transactions modules, and the six mansart-data modules (dialect SPI, H2 and PostgreSQL dialects,
     * core, processor, cdi).
     */
    private static final String[] SHIPPED_PACKAGES = {
        "io.vidocq.mansart.pool", "io.vidocq.mansart.transactions", "io.vidocq.mansart.data"
    };

    private static final ImportOption NO_TESTS = new ImportOption.DoNotIncludeTests();

    private static boolean isAFixture(com.tngtech.archunit.core.importer.Location location) {
        return location.contains("/archfixture/") || location.contains("/ArchFixture");
    }

    /** The classes Mansart ships, test classes and fixtures left out. */
    static final JavaClasses SHIPPED = new ClassFileImporter()
            .withImportOption(NO_TESTS)
            .withImportOption(location -> !isAFixture(location))
            .importPackages(SHIPPED_PACKAGES);

    /**
     * The shipped classes plus the fixtures of this module: those of an {@code archfixture} package and those
     * named {@code ArchFixture*} (for a module whose package has no sub-package to hold them), each of which sits
     * in a shipped package and breaks one rule there.
     */
    static final JavaClasses SHIPPED_AND_FIXTURES = new ClassFileImporter()
            .withImportOption(location -> NO_TESTS.includes(location) || isAFixture(location))
            .importPackages(SHIPPED_PACKAGES);

    /**
     * The shipped classes without the documented holders of a {@code ThreadLocal}
     * ({@link ArchitectureRules#THREAD_LOCAL_HOLDERS}), the classes the thread-local rule is given.
     */
    static final JavaClasses SHIPPED_WITHOUT_THREAD_LOCAL_HOLDERS = new ClassFileImporter()
            .withImportOption(NO_TESTS)
            .withImportOption(location -> !isAFixture(location))
            .withImportOption(location -> !isAThreadLocalHolder(location))
            .importPackages(SHIPPED_PACKAGES);

    private static boolean isAThreadLocalHolder(com.tngtech.archunit.core.importer.Location location) {
        return ArchitectureRules.THREAD_LOCAL_HOLDERS.keySet().stream().anyMatch(holder -> {
            String path = holder.replace('.', '/');
            return location.contains(path + ".class") || location.contains(path + "$");
        });
    }

    private MansartClasses() {
        // Constants only.
    }
}
