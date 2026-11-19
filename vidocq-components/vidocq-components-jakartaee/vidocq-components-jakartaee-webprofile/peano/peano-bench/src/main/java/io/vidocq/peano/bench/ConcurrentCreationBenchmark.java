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
import java.util.concurrent.atomic.AtomicLong;
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.ValueExpression;
import org.openjdk.jmh.annotations.*;

/** Shared factory with caller-owned contexts, including globally distinct unique sources. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
@Fork(1)
public class ConcurrentCreationBenchmark {
    @State(Scope.Benchmark) public static class SharedFactory {
        @Param({"peano"}) public String engine;
        ExpressionFactory factory;
        final AtomicLong callers=new AtomicLong();
        @Setup(Level.Trial) public void setup() {
            Fixtures.verifyModules(); factory=Fixtures.factory(engine);
        }
    }

    @State(Scope.Thread) public static class Caller {
        @Param({"shared","rotating","unique"}) public String workload;
        ELContext context;
        long operand;
        private long sequence;
        private long uniqueBase;
        private String[] sources;
        @Setup(Level.Trial) public void setup(SharedFactory shared) {
            context=Fixtures.context(shared.factory);
            uniqueBase=shared.callers.getAndIncrement()*1_000_000_000_000L;
            sources=new String[64];
            for (int i=0;i<sources.length;i++) sources[i]="\u0024{a+"+i+"}";
        }
        private String nextSource() {
            return switch (workload) {
                case "shared" -> { operand=0; yield sources[0]; }
                case "rotating" -> { operand=(sequence++)&63; yield sources[(int)operand]; }
                case "unique" -> { operand=uniqueBase+sequence++; yield "\u0024{a+"+operand+"}"; }
                default -> throw new IllegalArgumentException(workload);
            };
        }
    }

    @Benchmark public ValueExpression create(SharedFactory shared,Caller caller) {
        return shared.factory.createValueExpression(caller.context,caller.nextSource(),Object.class);
    }
}
