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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * APT processor that generates one {@code @Named @Singleton} {@link javax.sql.DataSource} holder per
 * declared named datasource, so {@code @Inject @Named("X") DataSource} and
 * {@code @Repository(dataStore="X")} resolve at runtime to the pool registered under that name in
 * {@code NamedDataSourceRegistry}.
 *
 * <p>Datasource names are discovered from two build-visible sources, merged and deduplicated:
 * <ol>
 *   <li>the {@code @VidocqDataSources({...})} annotation (always build-visible);</li>
 *   <li>{@code vidocq.pool.<name>.url} keys parsed from the static {@code vidocq.properties} /
 *       {@code application.properties} on the build output (the Vidocq config convention).</li>
 * </ol>
 *
 * <p>Generation follows the Quarkus split: the <em>structure</em> (which datasources exist) is fixed
 * at build time so the beans are AOT-safe; the <em>values</em> (url/credentials) stay runtime via
 * MicroProfile Config. The generated holder is a plain Java source emitted through {@code Filer} —
 * no bytecode manipulation. Holders are emitted in the package of the {@code @VidocqDataSources}
 * anchor element, each carrying {@code @Named("X")} plus the {@code @ManagedDataSource} marker
 * qualifier (so a named datasource never pollutes the unqualified {@code @Default} resolution).
 *
 * <p>A module whose names are all in its properties needs no annotation: the compilation of its
 * {@code module-info.java} anchors them, in the first package of the module. A compilation without a
 * module declaration (the tests compiled into the module, a classpath build) takes the names of the
 * properties only alongside the annotation, so the tests of a module never generate a second bean per
 * name.
 */
// Every annotation, none included: javac runs a processor only when the sources carry an annotation it supports, and
// a module whose pools are all in its vidocq.properties carries no @VidocqDataSources (BUG-20261113-01). The
// processor claims nothing.
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class VidocqDataSourcesProcessor extends AbstractProcessor {

    private static final String ANNOTATION =
            "io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.VidocqDataSources";
    private static final String BASE_HOLDER =
            "io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.AbstractNamedDataSourceHolder";
    private static final String MARKER_QUALIFIER =
            "io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.ManagedDataSource";

    private static final String POOL_PREFIX = "vidocq.pool.";
    private static final String URL_SUFFIX  = ".url";
    private static final List<String> CONFIG_FILES = List.of("vidocq.properties", "application.properties");

    private Filer filer;
    private Messager messager;
    private Elements elements;
    private boolean generated;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.filer = env.getFiler();
        this.messager = env.getMessager();
        this.elements = env.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || generated) {
            return false;
        }
        TypeElement annType = elements.getTypeElement(ANNOTATION);
        if (annType == null) {
            return false;
        }
        Set<? extends Element> anchors = roundEnv.getElementsAnnotatedWith(annType);
        Set<String> names = new TreeSet<>();
        for (Element e : anchors) {
            names.addAll(readAnnotationNames(e));
        }
        names.addAll(readConfigNames());

        Element anchor = anchors.isEmpty() ? declaredModule(roundEnv) : anchorOf(anchors);
        if (anchor == null || names.isEmpty() && anchors.isEmpty()) {
            return false;
        }
        generated = true;
        String targetPackage = packageFor(anchor);
        if (targetPackage == null) {
            if (!anchors.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@VidocqDataSources on module " + anchor + " needs a package to hold the generated "
                                + "datasource beans, and the module declares none: annotate a class or a package instead",
                        anchor);
            }
            return false;
        }
        for (String name : names) {
            generateHolder(targetPackage, name, anchor);
        }
        return false;
    }

    /**
     * Holders are emitted in the package of the first annotated type or package; a module declaration has no package
     * of its own, so it only anchors when nothing else does.
     */
    private static Element anchorOf(Set<? extends Element> anchors) {
        for (Element candidate : anchors) {
            if (candidate.getKind() != ElementKind.MODULE) {
                return candidate;
            }
        }
        return anchors.iterator().next();
    }

    /**
     * Without the annotation, the module declaration this compilation compiles anchors the names of the properties.
     * A compilation without one, the tests compiled into a module or a classpath build, generates nothing: its
     * holders would be second beans of the names its module already serves.
     */
    private static Element declaredModule(RoundEnvironment roundEnv) {
        for (Element root : roundEnv.getRootElements()) {
            if (root instanceof ModuleElement module && !module.isUnnamed()) {
                return module;
            }
        }
        return null;
    }

    /**
     * The package that receives the holders: the one enclosing a type or package anchor, and for a
     * module declaration (which has no package) the first package of the module, in name order.
     * Returns {@code null} when the module has no package.
     */
    private String packageFor(Element anchor) {
        if (anchor instanceof ModuleElement module) {
            return module.getEnclosedElements().stream()
                    .filter(PackageElement.class::isInstance)
                    .map(e -> ((PackageElement) e).getQualifiedName().toString())
                    .filter(name -> !name.isEmpty())
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
        return elements.getPackageOf(anchor).getQualifiedName().toString();
    }

    /** Reads the {@code value()} array of {@code @VidocqDataSources} via the annotation mirror. */
    private List<String> readAnnotationNames(Element element) {
        for (AnnotationMirror am : element.getAnnotationMirrors()) {
            if (!am.getAnnotationType().toString().equals(ANNOTATION)) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : am.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("value")) {
                    return extractStringArray(entry.getValue());
                }
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStringArray(AnnotationValue value) {
        Object raw = value.getValue();
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return ((List<? extends AnnotationValue>) list).stream()
                .map(av -> String.valueOf(av.getValue()))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Parses {@code vidocq.pool.<name>.url} keys from the build-visible config files. Absent files
     * are silently ignored — the annotation remains the robust path.
     */
    private Set<String> readConfigNames() {
        Set<String> names = new TreeSet<>();
        for (String file : CONFIG_FILES) {
            try {
                FileObject fo = filer.getResource(StandardLocation.CLASS_OUTPUT, "", file);
                Properties props = new Properties();
                try (InputStream in = fo.openInputStream()) {
                    props.load(in);
                }
                for (String key : props.stringPropertyNames()) {
                    String name = namedDatasourceOf(key);
                    if (name != null) {
                        names.add(name);
                    }
                }
            } catch (IOException | IllegalArgumentException _) {
                // file absent or unreadable at this build phase — annotation still covers it
            }
        }
        return names;
    }

    /**
     * Returns the datasource name for a {@code vidocq.pool.<name>.url} key, or {@code null} for the
     * default ({@code vidocq.pool.url}) or any non-matching key. Only single-segment names match.
     */
    private static String namedDatasourceOf(String key) {
        if (key.length() <= POOL_PREFIX.length() + URL_SUFFIX.length()
                || !key.startsWith(POOL_PREFIX) || !key.endsWith(URL_SUFFIX)) {
            return null;
        }
        String name = key.substring(POOL_PREFIX.length(), key.length() - URL_SUFFIX.length());
        return name.indexOf('.') < 0 ? name : null;
    }

    private void generateHolder(String pkg, String name, Element origin) {
        String className = "_" + sanitize(name) + "$DataSource";
        String fqcn = pkg.isEmpty() ? className : pkg + "." + className;
        try {
            JavaFileObject jfo = filer.createSourceFile(fqcn, origin);
            try (PrintWriter pw = new PrintWriter(jfo.openWriter())) {
                if (!pkg.isEmpty()) {
                    pw.println("package " + pkg + ";");
                    pw.println();
                }
                pw.println("import jakarta.inject.Named;");
                pw.println("import jakarta.inject.Singleton;");
                pw.println();
                pw.println("/** Generated by " + getClass().getName() + " — do not edit. */");
                pw.println("@Named(\"" + name + "\")");
                // Marker qualifier: keeps this @Named holder out of the @Default candidate set, so an
                // unqualified @Inject DataSource still resolves to the @Default pool alone.
                pw.println("@" + MARKER_QUALIFIER);
                pw.println("@Singleton");
                // Declare DataSource as a DIRECT interface (the impls are inherited from BASE_HOLDER):
                // the Vauban indexer reads a bean's types from its own source element and does not walk
                // the interfaces of an external abstract superclass, so without this the holder would
                // have no javax.sql.DataSource bean type and @Inject @Named("X") DataSource stays
                // unsatisfied at build time.
                // NOT final: a CDI managed bean must stay subclassable. When the deployment has
                // interceptors (e.g. mansart-transactions' @Transactional), Vauban wraps beans for
                // interception, and a final bean fails the proxyability check at container build
                // (DefinitionException "must not be final"). Mirrors the non-final @Default MansartPoolHolder.
                pw.println("public class " + className + " extends " + BASE_HOLDER
                        + " implements javax.sql.DataSource {");
                pw.println("    public " + className + "() {");
                pw.println("        super(\"" + name + "\");");
                pw.println("    }");
                pw.println("}");
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate named DataSource holder " + fqcn + ": " + e.getMessage());
        }
    }

    /** Turns a datasource name into a valid Java identifier fragment for the class name. */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }
}
