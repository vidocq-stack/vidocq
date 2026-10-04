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
package io.vidocq.cervantes.cdi.internal;

import org.eclipse.microprofile.jwt.ClaimValue;

import java.util.function.Supplier;

/**
 * Lazy implementation of {@link ClaimValue}: {@link #getValue()} rereads the claim from the
 * token of the current query at each call (spec MicroProfile JWT 2.2 — a {@code ClaimValue}
 * injected into a {@code @ApplicationScoped} bean should reflect the active request).
 *
 * @param <T> type of the claim value
 */
public final class ClaimValueImpl<T> implements ClaimValue<T> {

    private final String name;
    private final Supplier<T> value;

    @SuppressWarnings("unchecked")
    ClaimValueImpl(String name, Supplier<?> value) {
        this.name = name;
        this.value = (Supplier<T>) value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public T getValue() {
        return value.get();
    }

    @Override
    public String toString() {
        return "ClaimValue[" + name + "=" + value.get() + "]";
    }
}
