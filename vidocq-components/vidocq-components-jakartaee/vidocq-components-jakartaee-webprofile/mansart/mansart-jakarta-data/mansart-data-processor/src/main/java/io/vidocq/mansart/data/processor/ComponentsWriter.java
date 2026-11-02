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

import javax.annotation.processing.Filer;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes, for each package with entities or repositories, a {@code _MansartComponents} class implementing
 * {@code io.vidocq.mansart.data.core.GeneratedComponents} (MANSART-038), and the {@code META-INF/services} file that
 * declares them. The class hands Mansart the entity models and creates the repository implementations by plain calls
 * compiled in the application's module: Mansart reaches them without reflection, so a named module needs no
 * {@code opens} or {@code exports} for them.
 *
 * <p>A class is written at the end of each round for what the round produced; a package that gets more in a later
 * round gets a second class ({@code _MansartComponents2}, …). The services file is a resource, written at the end.</p>
 */
final class ComponentsWriter {

    static final String SERVICE = "io.vidocq.mansart.data.core.GeneratedComponents";
    private static final String NAME = "_MansartComponents";

    private final SourceSink sink;
    private final Map<String, List<String[]>> entities = new LinkedHashMap<>();
    private final Map<String, List<String[]>> repositories = new LinkedHashMap<>();
    private final Map<String, Integer> written = new HashMap<>();
    private final List<String> providers = new ArrayList<>();

    ComponentsWriter(SourceSink sink) {
        this.sink = sink;
    }

    /** An entity of {@code pkg}, referenced as {@code entity}, whose model is {@code metamodel.$MODEL}. */
    void entity(String pkg, String entity, String metamodel) {
        entities.computeIfAbsent(pkg, _ -> new ArrayList<>()).add(new String[] {entity, metamodel});
    }

    /** A repository of {@code pkg}, referenced as {@code repository}, implemented by {@code implementation}. */
    void repository(String pkg, String repository, String implementation) {
        repositories.computeIfAbsent(pkg, _ -> new ArrayList<>()).add(new String[] {repository, implementation});
    }

    /** Writes the classes of what this round produced. */
    void endOfRound() throws IOException {
        List<String> packages = new ArrayList<>(entities.keySet());
        for (String pkg : repositories.keySet()) {
            if (!packages.contains(pkg)) {
                packages.add(pkg);
            }
        }
        for (String pkg : packages) {
            write(pkg, entities.getOrDefault(pkg, List.of()), repositories.getOrDefault(pkg, List.of()));
        }
        entities.clear();
        repositories.clear();
    }

    /** Writes {@code META-INF/services/…GeneratedComponents}, when a class was written. */
    void writeServices(Filer filer) throws IOException {
        if (providers.isEmpty()) {
            return;
        }
        var file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + SERVICE);
        try (Writer w = file.openWriter()) {
            for (String provider : providers) {
                w.write(provider);
                w.write('\n');
            }
        }
    }

    private void write(String pkg, List<String[]> pkgEntities, List<String[]> pkgRepositories) throws IOException {
        int n = written.merge(pkg, 1, Integer::sum);
        String simple = n == 1 ? NAME : NAME + n;
        String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
        try (PrintWriter w = new PrintWriter(sink.createSource(fqn))) {
            if (!pkg.isEmpty()) {
                w.println("package " + pkg + ";");
                w.println();
            }
            w.println("@javax.annotation.processing.Generated(\"io.vidocq.mansart.data.processor.MansartProcessor\")");
            w.println("public final class " + simple + " implements " + SERVICE + " {");
            w.println();
            w.println("    public " + simple + "() {");
            w.println("    }");
            w.println();
            w.println("    @Override");
            w.println("    public io.vidocq.mansart.data.dialect.EntityModel<?> model(Class<?> entity) {");
            for (String[] e : pkgEntities) {
                w.println("        if (entity == " + e[0] + ".class) return " + e[1] + ".$MODEL;");
            }
            w.println("        return null;");
            w.println("    }");
            w.println();
            w.println("    @Override");
            w.println("    public Object repository(Class<?> repository, io.vidocq.mansart.data.core.RepositoryRuntime runtime) {");
            for (String[] r : pkgRepositories) {
                w.println("        if (repository == " + r[0] + ".class) return new " + r[1] + "(runtime);");
            }
            w.println("        return null;");
            w.println("    }");
            w.println("}");
        }
        providers.add(fqn);
    }
}
