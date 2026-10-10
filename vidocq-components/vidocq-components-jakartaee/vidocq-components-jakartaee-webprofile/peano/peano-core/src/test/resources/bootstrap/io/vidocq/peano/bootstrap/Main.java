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
package io.vidocq.peano.bootstrap;

import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;

/** Independent consumer: references only the Jakarta API. */
public final class Main {
    public static void main(String[] args) {
        var factory = ExpressionFactory.newInstance();
        var module = factory.getClass().getModule();
        if (!"io.vidocq.peano.core".equals(module.getName())
                || module.getDescriptor().isAutomatic()
                || !module.getDescriptor().exports().isEmpty()
                || !module.getDescriptor().opens().isEmpty()) {
            throw new AssertionError("Provider module is not encapsulated");
        }
        var context = new StandardELContext(factory);
        var expression = factory.createValueExpression(context, "${1 + 2 * 3}", Object.class);
        if (!Long.valueOf(7L).equals(expression.getValue(context))) {
            throw new AssertionError("Unexpected evaluation result");
        }
        System.out.println("PEANO_MODULE_OK");
    }
}

