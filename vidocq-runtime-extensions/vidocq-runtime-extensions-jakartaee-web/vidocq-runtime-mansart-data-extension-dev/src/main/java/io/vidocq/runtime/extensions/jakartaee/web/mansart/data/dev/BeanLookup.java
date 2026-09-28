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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Set;

/**
 * The bean of a repository, resolved at each call (spec §3): {@code bm.getReference(bm.resolve(bm.getBeans(type)),
 * type, cc)}. An action may create a bean, unlike a sample; a Mansart repository is a singleton.
 */
@FunctionalInterface
interface BeanLookup {

    /**
     * The one bean of {@code type}.
     *
     * @throws NoBean when there is none, or more than one
     */
    Object reference(Class<?> type);

    /** No bean, or an ambiguous one: the call answers {@code no bean for <Repository>}. */
    final class NoBean extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoBean() {
            super(null, null, false, false);
        }
    }

    /** The beans of {@code beans}. */
    static BeanLookup of(BeanManager beans) {
        return type -> {
            Set<Bean<?>> found = beans.getBeans(type);
            if (found.isEmpty()) {
                throw new NoBean();
            }
            Bean<?> bean;
            try {
                bean = beans.resolve(found);
            } catch (AmbiguousResolutionException ambiguous) {
                throw new NoBean();
            }
            if (bean == null) {
                throw new NoBean();
            }
            return beans.getReference(bean, type, beans.createCreationalContext(bean));
        };
    }
}
