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

import java.util.Objects;
import jakarta.el.ELContext;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.ValueExpression;
import jakarta.el.ValueReference;

/**
 * Standard expression protocol for application-owned classes emitted at build time.
 * The generated subclass evaluates directly; this base never parses or compiles.
 * Parsed identity is an opaque canonical key supplied by the compiler.
 */
public abstract class CompiledValueExpression extends ValueExpression {
    private static final long serialVersionUID=1L;
    private static final String CONTEXT="context";
    private final String source;
    private final String identity;
    private final Class<?> expectedType;
    private final boolean literalText;
    private final Class<?> rawType;

    /** Initializes constant-expression metadata; rawType may be null for null literals. */
    protected CompiledValueExpression(String source,String identity,Class<?> expectedType,
            boolean literalText,Class<?> rawType) {
        this.source=Objects.requireNonNull(source,"source");
        this.identity=Objects.requireNonNull(identity,"identity");
        this.expectedType=Objects.requireNonNull(expectedType,"expectedType");
        this.literalText=literalText;
        this.rawType=rawType;
    }

    /** Evaluates the emitted body without expected-type conversion or listener notifications. */
    protected abstract Object evaluate(ELContext context);

    /** Captured bindings contribute to identity when a generated expression uses them. */
    protected Object bindingIdentity() { return java.util.Map.of(); }

    @Override @SuppressWarnings("unchecked")
    public final <T> T getValue(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        context.notifyBeforeEvaluation(source);
        T value=(T)context.convertToType(evaluate(context),expectedType);
        context.notifyAfterEvaluation(source);
        return value;
    }
    @Override public void setValue(ELContext context,Object value) {
        Objects.requireNonNull(context,CONTEXT);
        throw new PropertyNotWritableException("This expression is read-only");
    }
    @Override public boolean isReadOnly(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        return true;
    }
    @Override public Class<?> getType(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        return rawType;
    }
    @Override public ValueReference getValueReference(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        return null;
    }
    @Override public final Class<?> getExpectedType() { return expectedType; }
    @Override public final String getExpressionString() { return source; }
    @Override public final boolean isLiteralText() { return literalText; }

    @Override public final boolean equals(Object other) {
        return other instanceof CompiledValueExpression expression
                && expectedType==expression.expectedType && identity.equals(expression.identity)
                && Objects.equals(bindingIdentity(),expression.bindingIdentity());
    }
    @Override public final int hashCode() {
        return Objects.hash(expectedType,identity,bindingIdentity());
    }

    private void readObject(java.io.ObjectInputStream input)
            throws java.io.IOException,ClassNotFoundException {
        input.defaultReadObject();
        if (source==null || identity==null || expectedType==null)
            throw new java.io.InvalidObjectException("Invalid compiled expression metadata");
    }
}

