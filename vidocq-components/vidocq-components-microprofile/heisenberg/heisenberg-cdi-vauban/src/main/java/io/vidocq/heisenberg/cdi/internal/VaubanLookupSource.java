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
package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.FallbackResolver;
import io.vidocq.vauban.core.access.ModuleLookups;
import java.lang.invoke.MethodHandles;
import java.util.Optional;

/**
 * Lets {@code FallbackResolver} reach a fallback method of a module that opens nothing, through
 * the lookup Vauban takes from the generated {@code _VaubanComponents} of the bean's package
 * ({@code ModuleLookups}, exported by {@code io.vidocq.vauban.core} to this module only).
 *
 * <p>{@code vauban-core} is an optional dependency ({@code requires static}): under another
 * container the lookup source answers nothing, and the resolver keeps its own path.</p>
 */
final class VaubanLookupSource {

    private VaubanLookupSource() {
    }

    /** Installs the source; idempotent. Called from the extension and the interceptor. */
    static void install() {
        FallbackResolver.useLookupSource(VaubanLookupSource::lookupFor);
    }

    private static Optional<MethodHandles.Lookup> lookupFor(Class<?> beanClass) {
        try {
            return ModuleLookups.lookupFor(beanClass);
        } catch (LinkageError notVauban) {
            return Optional.empty();
        }
    }
}
