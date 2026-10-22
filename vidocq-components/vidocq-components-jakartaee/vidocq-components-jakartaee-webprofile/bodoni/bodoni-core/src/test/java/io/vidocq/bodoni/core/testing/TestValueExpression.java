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

package io.vidocq.bodoni.core.testing;

import jakarta.el.ELContext;
import jakarta.el.ValueExpression;

/** A {@link ValueExpression} double returning a fixed value. */
public class TestValueExpression extends ValueExpression {

    private static final long serialVersionUID = 1L;

    private final transient Object value;

    public TestValueExpression(Object value) {
        this.value = value;
    }

    @Override
    public Object getValue(ELContext context) {
        return value;
    }

    @Override
    public void setValue(ELContext context, Object newValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isReadOnly(ELContext context) {
        return true;
    }

    @Override
    public Class<?> getType(ELContext context) {
        return value == null ? null : value.getClass();
    }

    @Override
    public Class<?> getExpectedType() {
        return Object.class;
    }

    @Override
    public String getExpressionString() {
        return "#{test}";
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean isLiteralText() {
        return false;
    }
}
