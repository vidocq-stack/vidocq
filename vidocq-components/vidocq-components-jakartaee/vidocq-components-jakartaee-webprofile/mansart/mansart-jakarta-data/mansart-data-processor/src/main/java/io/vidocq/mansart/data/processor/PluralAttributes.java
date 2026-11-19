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
package io.vidocq.mansart.data.processor;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * A plural attribute — {@code @ElementCollection}, {@code @OneToMany}, {@code @ManyToMany}, or a field whose type is
 * a collection or a map — is not a column of the entity's table: Mansart Data neither reads nor writes it, and leaves
 * it to the persistence provider (MANSART-049). The metamodel and the derived queries leave it out.
 */
final class PluralAttributes {

    private static final List<String> ANNOTATIONS = List.of("jakarta.persistence.ElementCollection",
            "jakarta.persistence.OneToMany", "jakarta.persistence.ManyToMany");

    private PluralAttributes() {
    }

    /** What makes {@code field} plural, worded for a message ({@code "an @ElementCollection"}), else {@code null}. */
    static String kind(VariableElement field, Elements elements, Types types) {
        for (AnnotationMirror mirror : field.getAnnotationMirrors()) {
            String annotation = ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
            if (ANNOTATIONS.contains(annotation)) {
                return "an @" + annotation.substring(annotation.lastIndexOf('.') + 1);
            }
        }
        if (isSubtypeOf(field.asType(), "java.util.Collection", elements, types)) return "a collection";
        if (isSubtypeOf(field.asType(), "java.util.Map", elements, types)) return "a map";
        return null;
    }

    private static boolean isSubtypeOf(TypeMirror type, String fqn, Elements elements, Types types) {
        TypeElement target = elements.getTypeElement(fqn);
        return target != null && type.getKind() == TypeKind.DECLARED
                && types.isSubtype(types.erasure(type), types.erasure(target.asType()));
    }
}
