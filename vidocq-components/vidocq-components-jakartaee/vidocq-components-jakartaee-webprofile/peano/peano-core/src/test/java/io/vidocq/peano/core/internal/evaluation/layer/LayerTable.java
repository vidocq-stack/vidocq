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
package io.vidocq.peano.core.internal.evaluation.layer;

import io.vidocq.peano.spi.AccessorTable;
import jakarta.el.ELContext;

/** The accessor table of {@link LayerBean}: {@code name} is "layer". */
public final class LayerTable extends AccessorTable {
    /** Creates the table. */
    public LayerTable() {
        // Built by LayerProvider.
    }

    @Override
    public Class<?> targetType() {
        return LayerBean.class;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        if (!(base instanceof LayerBean) || !"name".equals(property)) return null;
        context.setPropertyResolved(base, property);
        return "layer";
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        return null;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        // Read-only.
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        return false;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return null;
    }
}
