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
package io.vidocq.bodoni.peano.internal;

import java.util.List;
import jakarta.servlet.jsp.jstl.core.LoopTagStatus;
import io.vidocq.peano.spi.MethodDescriptor;

/**
 * Accessor table for {@link LoopTagStatus}, the {@code varStatus} object of Tags 3.0 section 6.1.
 * The JSTL API implements it with an anonymous class, which no table can name, so the table is
 * keyed on the interface and reached by Peano's supertype lookup. All properties are read-only:
 * current, index, count, first, last, begin, end and step. The matching getters
 * (getCurrent, getIndex, getCount, isFirst, isLast, getBegin, getEnd, getStep) are callable.
 */
final class LoopTagStatusAccessorTable extends ReadOnlyAccessorTable {
    private static final List<String> PROPERTIES =
            List.of("current", "index", "count", "first", "last", "begin", "end", "step");
    private static final MethodDescriptor[] METHODS = {
        descriptor("getCurrent", Object.class),
        descriptor("getIndex", int.class),
        descriptor("getCount", int.class),
        descriptor("isFirst", boolean.class),
        descriptor("isLast", boolean.class),
        descriptor("getBegin", Integer.class),
        descriptor("getEnd", Integer.class),
        descriptor("getStep", Integer.class),
    };

    LoopTagStatusAccessorTable() {
        super(LoopTagStatus.class, PROPERTIES, METHODS);
    }

    @Override
    protected String subject() {
        return "Loop status";
    }

    @Override
    protected Object read(Object base, int index) {
        var status = (LoopTagStatus) base;
        return switch (index) {
            case 0 -> status.getCurrent();
            case 1 -> status.getIndex();
            case 2 -> status.getCount();
            case 3 -> status.isFirst();
            case 4 -> status.isLast();
            case 5 -> status.getBegin();
            case 6 -> status.getEnd();
            default -> status.getStep();
        };
    }
}
