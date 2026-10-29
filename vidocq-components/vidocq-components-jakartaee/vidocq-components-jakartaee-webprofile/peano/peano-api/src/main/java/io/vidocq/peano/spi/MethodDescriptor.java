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
package io.vidocq.peano.spi;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Objects;
import jakarta.el.MethodInfo;

/**
 * Immutable build-time signature and annotation data for a directly callable method.
 *
 * @param name method name
 * @param returnType declared erased return type
 * @param parameterTypes declared erased parameter types, defensively copied
 * @param varargs whether the final array parameter accepts variable arity
 * @param staticMethod whether the method is static
 * @param annotations runtime annotation literals, defensively copied
 */
public record MethodDescriptor(String name, Class<?> returnType, Class<?>[] parameterTypes,
        boolean varargs, boolean staticMethod, Annotation[] annotations) {
    public MethodDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnType, "returnType");
        parameterTypes = parameterTypes.clone();
        annotations = annotations.clone();
        for (Class<?> parameter : parameterTypes) Objects.requireNonNull(parameter, "parameter");
        if (varargs && (parameterTypes.length == 0 || !parameterTypes[parameterTypes.length-1].isArray())) {
            throw new IllegalArgumentException("Varargs descriptor requires a final array parameter");
        }
    }

    @Override public Class<?>[] parameterTypes() { return parameterTypes.clone(); }
    @Override public Annotation[] annotations() { return annotations.clone(); }

    /** @return fresh Jakarta metadata with a defensive parameter array */
    public MethodInfo methodInfo() { return new MethodInfo(name,returnType,parameterTypes.clone()); }

    @Override public boolean equals(Object other) {
        return other instanceof MethodDescriptor(var otherName, var otherReturn, var otherParameters,
                var otherVarargs, var otherStatic, var otherAnnotations)
                && name.equals(otherName) && returnType == otherReturn && varargs == otherVarargs
                && staticMethod == otherStatic && Arrays.equals(parameterTypes, otherParameters)
                && Arrays.equals(annotations, otherAnnotations);
    }

    @Override public int hashCode() {
        return Objects.hash(name,returnType,varargs,staticMethod,
                Arrays.hashCode(parameterTypes),Arrays.hashCode(annotations));
    }

    @Override public String toString() {
        return "MethodDescriptor[name=" + name + ", returnType=" + returnType
                + ", parameterTypes=" + Arrays.toString(parameterTypes) + ", varargs=" + varargs
                + ", staticMethod=" + staticMethod + ", annotations=" + Arrays.toString(annotations) + "]";
    }
}

