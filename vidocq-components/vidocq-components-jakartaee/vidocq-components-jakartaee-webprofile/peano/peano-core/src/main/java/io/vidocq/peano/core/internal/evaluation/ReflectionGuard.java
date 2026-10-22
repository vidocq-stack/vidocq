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
package io.vidocq.peano.core.internal.evaluation;

import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import jakarta.el.ELClass;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;

/** Stops API member fallback after generated and custom resolvers have had their turn. */
public final class ReflectionGuard extends ELResolver {
    private static final String CONTEXT = "context";

    public static boolean denied() {
        return switch (System.getProperty("peano.reflection","allow")) {
            case "allow" -> false;
            case "deny" -> true;
            default -> throw new ELException("Invalid peano.reflection value; expected allow or deny");
        };
    }

    public static void requireAllowed(Object base) {
        if (base != null && denied())
            requireAllowedType(base instanceof ELClass type ? type.getKlass() : base.getClass());
    }

    public static void requireAllowedType(Class<?> type) {
        if (denied()) throw new ELException("Reflection fallback denied for "+type.getName()
                +"; register a generated accessor provider or a custom resolver");
    }

    private static void property(ELContext context,Object base) {
        java.util.Objects.requireNonNull(context,CONTEXT);
        if (base==null || base instanceof Map<?,?> || base instanceof List<?>
                || base instanceof ResourceBundle || base.getClass().isArray()) return;
        requireAllowed(base);
    }

    @Override public Object getValue(ELContext context,Object base,Object key) {
        property(context,base);
        return null;
    }
    @Override public Class<?> getType(ELContext context,Object base,Object key) {
        property(context,base);
        return null;
    }
    @Override public void setValue(ELContext context,Object base,Object key,Object value) {
        property(context,base);
    }
    @Override public boolean isReadOnly(ELContext context,Object base,Object key) {
        property(context,base);
        return false;
    }
    @Override public Object invoke(ELContext context,Object base,Object method,Class<?>[] types,Object[] arguments) {
        java.util.Objects.requireNonNull(context,CONTEXT);
        requireAllowed(base);
        return null;
    }
    /** Metadata only: no member is accessed, and CompositeELResolver asks every resolver (EL-020). */
    @Override public Class<?> getCommonPropertyType(ELContext context,Object base) {
        java.util.Objects.requireNonNull(context,CONTEXT);
        return null;
    }
}

