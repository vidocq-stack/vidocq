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
package io.vidocq.runtime.codegen.commons;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * Test-only processor that exercises {@link ModuleInfoRequirements#verify} against a FIXED requirement
 * set ({@code requires java.sql} + an unqualified {@code opens db.migration}). The tests vary the
 * sample module-info to include or omit those directives and assert the resulting diagnostics.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class RequirementCheckTestProcessor extends AbstractProcessor {

    static final Set<Requirement> REQUIRED = Set.of(
            Requirement.requires("java.sql", "the generated @Named DataSource holder implements javax.sql.DataSource"),
            Requirement.opens("db.migration", "Flyway/Liquibase scan classpath migrations"));

    private boolean done;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (done || roundEnv.processingOver() || roundEnv.getRootElements().isEmpty()) {
            return false;
        }
        ModuleInfoRequirements.verify(processingEnv, roundEnv.getRootElements().iterator().next(), REQUIRED);
        done = true;
        return false;
    }
}
