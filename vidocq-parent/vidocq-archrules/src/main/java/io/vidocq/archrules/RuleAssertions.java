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
package io.vidocq.archrules;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * For a brick's own tests of its rules: a rule that passes on the shipped classes proves nothing unless it fails
 * on a class that breaks it, so a brick tests each rule on small fixture classes that break it and that keep it.
 */
public final class RuleAssertions {

    /** Fails unless {@code rule} is violated by {@code classes}. */
    public static void assertViolated(ArchRule rule, Class<?>... classes) {
        if (!rule.evaluate(new ClassFileImporter().importClasses(classes)).hasViolation()) {
            throw new AssertionError("expected a violation of: " + rule.getDescription());
        }
    }

    /** Fails, with the report of the violations, unless {@code rule} holds for {@code classes}. */
    public static void assertKept(ArchRule rule, Class<?>... classes) {
        var result = rule.evaluate(new ClassFileImporter().importClasses(classes));
        if (result.hasViolation()) {
            throw new AssertionError(result.getFailureReport().toString());
        }
    }

    private RuleAssertions() {
        // Helpers only.
    }
}
