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

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

/**
 * The name of an entity's Jakarta Data metamodel class: {@code _Book} for {@code shop.Book}, in the entity's
 * package. A nested entity {@code shop.Library.Shelf} gets {@code shop._Library_Shelf}: its binary name with
 * {@code _} where it has {@code $}, like the implementation of a nested repository, so that two nested entities
 * of one package never share a metamodel (MANSART-050). {@code EntityModels} in {@code mansart-data-core} looks
 * the class up under the same name at run time.
 */
final class MetamodelName {

    private MetamodelName() {
    }

    /** The package of {@code type}, through the classes it is nested in; empty for the unnamed package. */
    static String packageOf(Element type) {
        Element enclosing = type;
        while (!(enclosing instanceof PackageElement)) {
            enclosing = enclosing.getEnclosingElement();
        }
        return ((PackageElement) enclosing).getQualifiedName().toString();
    }

    /** How {@code entity} is named from its own package: {@code Book}, {@code Library.Shelf}. */
    static String entityFromItsPackage(TypeElement entity) {
        String pkg = packageOf(entity);
        String qualified = entity.getQualifiedName().toString();
        return pkg.isEmpty() ? qualified : qualified.substring(pkg.length() + 1);
    }

    /** The metamodel's simple name: {@code _Book}, {@code _Library_Shelf}. */
    static String simple(TypeElement entity) {
        return "_" + entityFromItsPackage(entity).replace('.', '_');
    }

    /** The metamodel's qualified name. */
    static String qualified(TypeElement entity) {
        String pkg = packageOf(entity);
        return pkg.isEmpty() ? simple(entity) : pkg + "." + simple(entity);
    }
}
