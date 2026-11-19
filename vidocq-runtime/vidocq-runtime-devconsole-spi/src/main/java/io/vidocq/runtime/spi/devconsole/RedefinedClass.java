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
package io.vidocq.runtime.spi.devconsole;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One class a redefinition changed, with the methods whose code changed.
 *
 * @param type    the class, as the application layer loaded it
 * @param methods the names of the changed methods, in the class's order: {@code <init>} for a constructor,
 *                {@code <clinit>} never (a static initializer that changes swaps the layer), and the synthetic
 *                {@code lambda$<method>$<n>} methods javac compiles lambda bodies to
 */
public record RedefinedClass(Class<?> type, Set<String> methods) {

    public RedefinedClass {
        Objects.requireNonNull(type, "type");
        methods = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(methods));
    }

    /**
     * {@code true} when every changed method is a lambda body: the next event runs the new code, and a framework has
     * nothing to rebuild.
     */
    public boolean onlyLambdaBodies() {
        return !methods.isEmpty() && methods.stream().allMatch(name -> name.startsWith("lambda$"));
    }
}
