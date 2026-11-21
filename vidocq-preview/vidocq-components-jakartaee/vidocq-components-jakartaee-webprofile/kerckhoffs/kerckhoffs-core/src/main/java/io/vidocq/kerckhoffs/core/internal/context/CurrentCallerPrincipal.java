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
package io.vidocq.kerckhoffs.core.internal.context;

import java.security.Principal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@code Principal} a CDI application injects (Jakarta Security 4.0, section 1.2.4): one object, injected once,
 * that answers for the caller of the request being served when it is asked. An unauthenticated caller has no name.
 * It is compared by name, as a principal is.
 */
public final class CurrentCallerPrincipal implements Principal {

    private final Supplier<Optional<Principal>> caller;

    /** @param caller the caller of the request being served, empty when nobody is authenticated */
    public CurrentCallerPrincipal(Supplier<Optional<Principal>> caller) {
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    @Override
    public String getName() {
        return caller.get().map(Principal::getName).orElse(null);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CurrentCallerPrincipal that && Objects.equals(getName(), that.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getName());
    }

    @Override
    public String toString() {
        return String.valueOf(getName());
    }
}
