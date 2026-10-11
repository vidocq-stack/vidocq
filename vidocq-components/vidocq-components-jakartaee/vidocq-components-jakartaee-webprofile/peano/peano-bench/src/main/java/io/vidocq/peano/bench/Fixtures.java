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

package io.vidocq.peano.bench;

import java.util.ServiceLoader;
import java.util.List;
import io.vidocq.peano.bench.model.Model;
import jakarta.el.*;

final class Fixtures {
    static final String SOURCE="${(a+2)*3}";
    static final List<String> CASES=List.of("arithmetic","property","lambda","collection");
    static String source(String fixture) {
        return switch (fixture) {
            case "arithmetic" -> SOURCE;
            case "property" -> "${model.value}";
            case "lambda" -> "${(x -> x+1)(a)}";
            case "collection" -> "${[a,2,3]}";
            default -> throw new IllegalArgumentException("Unknown fixture "+fixture);
        };
    }
    static Object expected(String fixture) {
        return switch (fixture) {
            case "arithmetic" -> 27L;
            case "property" -> 7L;
            case "lambda" -> 8L;
            case "collection" -> List.of(7L,2L,3L);
            default -> throw new IllegalArgumentException("Unknown fixture "+fixture);
        };
    }
    static String binaryName(String fixture) {
        return "io.vidocq.peano.bench.Generated"+Character.toUpperCase(fixture.charAt(0))+fixture.substring(1);
    }
    static void verifyModules() {
        Module module=Fixtures.class.getModule();
        if (!module.isNamed() || module.getLayer().findModule("io.vidocq.peano.compiler").isPresent())
            throw new IllegalStateException("Benchmark execution requires named modules with the compiler absent");
    }
    static ExpressionFactory factory(String engine) {
        String fragment=engine.equals("expressly") ? "expressly" : "peano";
        return ServiceLoader.load(ExpressionFactory.class).stream()
                .filter(provider->provider.type().getName().contains(fragment))
                .findFirst().orElseThrow().get();
    }
    static StandardELContext context(ExpressionFactory factory) {
        var context=new StandardELContext(factory);
        context.putContext(ExpressionFactory.class,factory);
        context.getVariableMapper().setVariable("a",factory.createValueExpression(7L,Long.class));
        context.getVariableMapper().setVariable("model",factory.createValueExpression(new Model(7),Model.class));
        return context;
    }
    private Fixtures() {}
}
