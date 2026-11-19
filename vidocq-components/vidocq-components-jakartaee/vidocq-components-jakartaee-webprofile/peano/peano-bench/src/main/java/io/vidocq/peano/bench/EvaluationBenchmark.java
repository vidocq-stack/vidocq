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

import java.util.concurrent.TimeUnit;
import jakarta.el.*;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
@Fork(2)
public class EvaluationBenchmark {
    @Param({"peano","expressly","compiled"})
    public String engine;
    @Param({"arithmetic","property","lambda","collection"})
    public String fixture;
    private ELContext context;
    private ValueExpression expression;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Fixtures.verifyModules();
        var factory=Fixtures.factory(engine);
        context=Fixtures.context(factory);
        expression=engine.equals("compiled")
                ? (ValueExpression)Class.forName(Fixtures.binaryName(fixture))
                    .getConstructor(ELContext.class,Class.class).newInstance(context,Object.class)
                : factory.createValueExpression(context,Fixtures.source(fixture),Object.class);
        if (!expression.getClass().getModule().isNamed())
            throw new IllegalStateException("Expression implementation must remain on the module path");
        if (!Fixtures.expected(fixture).equals(expression.getValue(context)))
            throw new IllegalStateException("Benchmark engines must agree before measurement");
    }

    @Benchmark public Object evaluate() { return expression.getValue(context); }
}
