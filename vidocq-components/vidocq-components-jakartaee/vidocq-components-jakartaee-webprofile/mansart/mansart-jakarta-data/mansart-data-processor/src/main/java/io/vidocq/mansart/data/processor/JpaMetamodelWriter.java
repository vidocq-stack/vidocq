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

import io.vidocq.mansart.data.processor.EntityScanner.AttributeDescriptor;
import io.vidocq.mansart.data.processor.EntityScanner.EntityDescriptor;

import javax.annotation.processing.Filer;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Writes the JPA-standard static metamodel class {@code Book_} for an entity {@code Book}.
 * Only emitted when {@code jakarta.persistence-api} is on the user's compile classpath.
 */
final class JpaMetamodelWriter {

    private final Filer filer;

    JpaMetamodelWriter(Filer filer) { this.filer = filer; }

    void write(EntityDescriptor e) throws IOException {
        String pkg = packageOf(e.type().getQualifiedName().toString());
        String simple = e.type().getSimpleName().toString();
        String className = simple + "_";
        String fqn = pkg.isEmpty() ? className : pkg + "." + className;

        JavaFileObject file = filer.createSourceFile(fqn, e.type());
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            if (!pkg.isEmpty()) {
                w.println("package " + pkg + ";");
                w.println();
            }
            w.println("import jakarta.persistence.metamodel.SingularAttribute;");
            w.println("import jakarta.persistence.metamodel.StaticMetamodel;");
            w.println("import javax.annotation.processing.Generated;");
            w.println();
            w.println("@Generated(\"io.vidocq.mansart.data.processor.MansartProcessor\")");
            w.println("@StaticMetamodel(" + simple + ".class)");
            w.println("public abstract class " + className + " {");
            w.println();
            for (AttributeDescriptor a : e.attributes()) {
                String boxed = MansartMetamodelWriter.box(a.javaTypeFqn());
                w.println("    public static volatile SingularAttribute<" + simple + ", " + boxed + "> " + a.name() + ";");
            }
            w.println();
            for (AttributeDescriptor a : e.attributes()) {
                w.println("    public static final String " + screamingSnake(a.name()) + " = \"" + a.name() + "\";");
            }
            w.println("}");
        }
    }

    private static String screamingSnake(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }
}
