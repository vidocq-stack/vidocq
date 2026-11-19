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
package io.vidocq.peano.core.internal;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import jakarta.el.ELContext;
import jakarta.el.StandardELContext;
import io.vidocq.peano.core.internal.evaluation.Bindings;
import io.vidocq.peano.core.internal.syntax.ExpressionParser;
import io.vidocq.peano.core.internal.syntax.Template;
import org.openjdk.jmh.annotations.*;

/** Diagnostic components patched into core only by run-analysis-bench.sh. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
@Fork(1)
public class CreationAnalysisBenchmark {
    @Param({"arithmetic","lambda"}) public String fixture;
    private String source;
    private Template template;
    private ELContext context;

    @Setup(Level.Trial) public void setup() {
        if (!getClass().getModule().isNamed()) throw new IllegalStateException("Named module required");
        source=switch (fixture) {
            case "arithmetic" -> "\u0024{(a+2)*3}";
            case "lambda" -> "\u0024{(x -> x+1)(a)}";
            default -> throw new IllegalArgumentException(fixture);
        };
        var factory=new PeanoExpressionFactory();
        var selected=new StandardELContext(factory);
        selected.putContext(jakarta.el.ExpressionFactory.class,factory);
        selected.getVariableMapper().setVariable("a",factory.createValueExpression(7L,Long.class));
        context=selected;
        template=ExpressionParser.parse(source);
    }

    @Benchmark public Template parse() { return ExpressionParser.parse(source); }
    @Benchmark public Template identity() { return ExpressionIdentity.template(template); }
    @Benchmark public Map<?,?> captureVariables() { return Bindings.capture(template,context); }
    @Benchmark public Map<?,?> captureFunctions() { return Bindings.captureFunctions(template,context); }
}
