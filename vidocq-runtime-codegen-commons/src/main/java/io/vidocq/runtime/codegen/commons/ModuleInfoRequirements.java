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

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.ModuleElement.OpensDirective;
import javax.lang.model.element.ModuleElement.RequiresDirective;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies, at annotation-processing time, that the {@code module-info.java} of the module under
 * compilation declares the JPMS directives a Vidocq extension needs at runtime — the {@code requires}
 * and {@code opens} that compile fine on the classpath/TCK but break only on the module path (a real
 * {@code docker compose up} / jlink boot).
 *
 * <p>A processor that generates code into the consumer module (or scans its packages) knows exactly
 * what the runtime will need; it expresses that as a set of {@link Requirement}s and calls
 * {@link #verify}, which emits a {@link Diagnostic.Kind#ERROR} per missing directive, failing the
 * compile with a copy-pasteable fix.</p>
 *
 * <p>An <em>unnamed</em> consumer module (a classpath build, e.g. some tests/TCK runs) is skipped:
 * there is no module encapsulation to satisfy, so the directives are neither present nor needed.</p>
 */
public final class ModuleInfoRequirements {

    private ModuleInfoRequirements() {
    }

    /**
     * Returns the subset of {@code required} directives the consumer module does NOT satisfy. Pure —
     * reads {@link ModuleElement#getDirectives()} only, never touches the {@code Messager}. Returns an
     * empty list when {@code consumer} is {@code null} or unnamed (classpath build — nothing to check).
     */
    public static List<Requirement> missing(ModuleElement consumer, Collection<Requirement> required) {
        if (consumer == null || consumer.isUnnamed()) {
            return List.of();
        }
        Set<String> requiresDeclared = new HashSet<>();
        // package -> declared target modules; an empty set means an UNQUALIFIED `opens <pkg>;`.
        Map<String, Set<String>> opensDeclared = new HashMap<>();
        for (ModuleElement.Directive directive : consumer.getDirectives()) {
            switch (directive.getKind()) {
                case REQUIRES -> requiresDeclared.add(
                        ((RequiresDirective) directive).getDependency().getQualifiedName().toString());
                case OPENS -> {
                    OpensDirective opens = (OpensDirective) directive;
                    String pkg = opens.getPackage().getQualifiedName().toString();
                    Set<String> targets = opens.getTargetModules() == null
                            ? Set.of()
                            : opens.getTargetModules().stream()
                                    .map(m -> m.getQualifiedName().toString())
                                    .collect(Collectors.toSet());
                    opensDeclared.merge(pkg, new HashSet<>(targets), (a, b) -> {
                        // Two opens of the same package: an unqualified one (empty) always wins.
                        if (a.isEmpty() || b.isEmpty()) {
                            return Set.of();
                        }
                        a.addAll(b);
                        return a;
                    });
                }
                default -> {
                    // exports / uses / provides are not part of the requirement model.
                }
            }
        }
        List<Requirement> unmet = new ArrayList<>();
        for (Requirement r : required) {
            if (!isSatisfied(r, requiresDeclared, opensDeclared)) {
                unmet.add(r);
            }
        }
        return unmet;
    }

    private static boolean isSatisfied(Requirement r, Set<String> requiresDeclared,
                                       Map<String, Set<String>> opensDeclared) {
        return switch (r.kind()) {
            case REQUIRES -> requiresDeclared.contains(r.name());
            case OPENS -> {
                if (!opensDeclared.containsKey(r.name())) {
                    yield false;
                }
                Set<String> declaredTargets = opensDeclared.get(r.name());
                if (r.to().isEmpty()) {
                    // Need an unqualified opens: a qualified declaration does NOT satisfy it.
                    yield declaredTargets.isEmpty();
                }
                // Need a qualified opens to specific modules: an unqualified declaration is broader and
                // covers it; otherwise every required target must be present.
                yield declaredTargets.isEmpty() || declaredTargets.containsAll(r.to());
            }
        };
    }

    /**
     * Verifies the directives of {@code consumer}, emitting an {@link Diagnostic.Kind#ERROR} for each
     * missing one (which fails the compilation). No-op when {@code consumer} is null/unnamed.
     */
    public static void verify(ProcessingEnvironment env, ModuleElement consumer,
                              Collection<Requirement> required) {
        for (Requirement r : missing(consumer, required)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, format(consumer, r));
        }
    }

    /**
     * Convenience overload: resolves the consumer module from {@code anchor} (any element being
     * processed) via {@link javax.lang.model.util.Elements#getModuleOf(Element)} and verifies it.
     */
    public static void verify(ProcessingEnvironment env, Element anchor, Collection<Requirement> required) {
        ModuleElement consumer = anchor == null ? null : env.getElementUtils().getModuleOf(anchor);
        verify(env, consumer, required);
    }

    private static String format(ModuleElement consumer, Requirement r) {
        StringBuilder sb = new StringBuilder()
                .append("module-info of '").append(consumer.getQualifiedName())
                .append("' is missing a directive required by a Vidocq extension: add  ")
                .append(r.directive());
        if (!r.reason().isBlank()) {
            sb.append("  (").append(r.reason()).append(')');
        }
        return sb.toString();
    }
}
