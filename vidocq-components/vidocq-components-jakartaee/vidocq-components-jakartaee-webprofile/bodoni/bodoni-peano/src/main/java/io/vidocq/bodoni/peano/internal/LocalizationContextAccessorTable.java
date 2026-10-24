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
import java.util.Locale;
import java.util.ResourceBundle;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import io.vidocq.peano.spi.MethodDescriptor;

/**
 * Accessor table for {@link LocalizationContext}, the {@code var} object of {@code fmt:setBundle}
 * and {@code fmt:bundle} (Tags 3.0 section 8.1.1). The class can be subclassed, so the table is
 * reached by Peano's supertype lookup. Both properties are read-only: resourceBundle and locale.
 * The matching getters (getResourceBundle, getLocale) are callable.
 */
final class LocalizationContextAccessorTable extends ReadOnlyAccessorTable {
    private static final List<String> PROPERTIES = List.of("resourceBundle", "locale");
    private static final MethodDescriptor[] METHODS = {
        descriptor("getResourceBundle", ResourceBundle.class),
        descriptor("getLocale", Locale.class),
    };

    LocalizationContextAccessorTable() {
        super(LocalizationContext.class, PROPERTIES, METHODS);
    }

    @Override
    protected Object read(Object base, int index) {
        var context = (LocalizationContext) base;
        return index == 0 ? context.getResourceBundle() : context.getLocale();
    }
}
