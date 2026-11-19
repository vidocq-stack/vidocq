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
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.ValueExpression;
import org.openjdk.jmh.annotations.*;

/** Full expression creation; unique sources include string construction in the timer. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=5,time=1)
@Measurement(iterations=8,time=1)
@Fork(3)
public class SourceCreationBenchmark {
    @Param({"peano","expressly"}) public String engine;
    @Param({"repeated","rotating","unique"}) public String workload;
    private ExpressionFactory factory;
    private ELContext context;
    private String[] sources;
    private long sequence;
    private long operand;

    @Setup(Level.Trial) public void setup() {
        Fixtures.verifyModules();
        factory=Fixtures.factory(engine); context=Fixtures.context(factory);
        sources=new String[64];
        for (int i=0;i<sources.length;i++) sources[i]="\u0024{a+"+i+"}";
        sequence=0;
    }

    private String nextSource() {
        return switch (workload) {
            case "repeated" -> { operand=0; yield sources[0]; }
            case "rotating" -> { operand=(sequence++) & 63; yield sources[(int)operand]; }
            case "unique" -> { operand=sequence++; yield "\u0024{a+"+operand+"}"; }
            default -> throw new IllegalArgumentException("Unknown workload "+workload);
        };
    }

    @Benchmark public ValueExpression create() {
        return factory.createValueExpression(context,nextSource(),Object.class);
    }

    /** Diagnostic source-generation control; do not subtract independent benchmark means. */
    @Benchmark public String sourceGeneration() { return nextSource(); }

    ELContext context() { return context; }
    long lastOperand() { return operand; }
}
