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
package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.Extensible;
import org.eclipse.microprofile.openapi.models.Reference;

/**
 * Base class for model POJOs that are both {@link Extensible} and {@link Reference}.
 *
 * @param <T> the self-type
 */
public abstract class AbstractExtensibleRef<T extends Extensible<T> & Reference<T>>
        extends AbstractExtensible<T>
        implements Reference<T> {

    private String ref;

    @Override
    public String getRef() {
        return ref;
    }

    @Override
    public void setRef(String ref) {
        // Expand short names to full $ref per spec
        if (ref != null && !ref.contains("/")) {
            ref = resolveComponentPrefix() + ref;
        }
        this.ref = ref;
    }

    /**
     * Sets a {@code $ref} exactly as written, without the short-name expansion of
     * {@link #setRef(String)}. Used for values read from a static file, where {@code Pet.yaml}
     * is a relative document reference and not the name of a component.
     */
    public static void setVerbatimRef(Reference<?> target, String ref) {
        if (target instanceof AbstractExtensibleRef<?> impl) {
            impl.ref = ref;
        } else {
            target.setRef(ref);
        }
    }

    /**
     * Returns the component prefix for short-name expansion.
     * Sub-classes override to return the correct prefix
     * (e.g. {@code "#/components/schemas/"}).
     */
    protected String resolveComponentPrefix() {
        return "#/components/";
    }
}

