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

package io.vidocq.peano.spi;

import java.util.*;
import jakarta.el.*;

/** Precompiled lambda bodies with lexical arguments owned by each lambda value. */
public abstract class CompiledLambdaValueExpression extends CompiledFunctionValueExpression {
    private static final long serialVersionUID=1L;

    protected CompiledLambdaValueExpression(String source,String identity,Class<?> expectedType,
            ELContext creationContext,String[] names,boolean composite,String[] functionKeys) {
        super(source,identity,expectedType,creationContext,names,composite,functionKeys);
    }

    protected final LambdaExpression createLambda(ELContext context,int ordinal,String[] parameters,String[] freeNames) {
        var captured=new HashMap<String,Object>();
        for (String name:freeNames)
            if (context.isLambdaArgument(name)) captured.put(name,context.getLambdaArgument(name));
        var body=new Body(this,ordinal,Collections.unmodifiableMap(captured));
        var lambda=new LambdaExpression(List.of(parameters),body);
        lambda.setELContext(context);
        return lambda;
    }

    protected abstract Object evaluateLambda(ELContext context,int ordinal);

    private static final class Body extends ValueExpression {
        private static final long serialVersionUID=1L;
        private final CompiledLambdaValueExpression owner;
        private final int ordinal;
        private final Map<String,Object> captured;
        Body(CompiledLambdaValueExpression owner,int ordinal,Map<String,Object> captured) {
            this.owner=owner; this.ordinal=ordinal; this.captured=captured;
        }
        @Override @SuppressWarnings("unchecked")
        public <T> T getValue(ELContext context) {
            Objects.requireNonNull(context,"context");
            context.enterLambdaScope(captured);
            try { return (T)owner.evaluateLambda(context,ordinal); }
            finally { context.exitLambdaScope(); }
        }
        @Override public void setValue(ELContext context,Object value) {
            throw new PropertyNotWritableException("Lambda body is read-only");
        }
        @Override public boolean isReadOnly(ELContext context) { return true; }
        @Override public Class<?> getType(ELContext context) {
            Object value=getValue(context); return value==null ? null : value.getClass();
        }
        @Override public Class<?> getExpectedType() { return Object.class; }
        @Override public String getExpressionString() { return owner.getExpressionString()+"#lambda:"+ordinal; }
        @Override public boolean isLiteralText() { return false; }
        @Override public boolean equals(Object other) {
            return other instanceof Body body && ordinal==body.ordinal && owner.equals(body.owner)
                    && captured.equals(body.captured);
        }
        @Override public int hashCode() { return Objects.hash(owner,ordinal,captured); }
    }
}
