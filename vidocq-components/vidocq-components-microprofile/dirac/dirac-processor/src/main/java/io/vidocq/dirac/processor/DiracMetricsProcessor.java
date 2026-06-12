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
package io.vidocq.dirac.processor;

import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generates {@code <Bean>$$DiracMetrics} companion sources for classes carrying
 * {@code @Timed}/{@code @Counted}/{@code @Gauge} — names, units, tags and scopes
 * resolved at compile time with the exact rules of {@code DiracExtension}'s startup
 * scan, gauges as direct functional accessors (CG-05, APT-first rule).
 *
 * <p><strong>Safety valve</strong>: every definition the startup scan would reject at
 * runtime (invalid gauge signature, malformed or reserved tags) and every construct a
 * source companion cannot express (private gauge method, nested bean class) produces
 * a compiler NOTE and skips the class — the reflective scan fallback preserves exact
 * runtime behaviour, including the deployment errors the MP Metrics TCK expects.</p>
 */
public final class DiracMetricsProcessor extends AbstractProcessor {

    private static final String SUFFIX = "$$DiracMetrics";
    private static final String INTERCEPTOR_FQN = "jakarta.interceptor.Interceptor";

    private final Set<String> companionFqns = new TreeSet<>();
    private boolean servicesWritten;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // Class-level stereotypes meta-annotated with @Timed/@Counted must be seen,
        // so the processor inspects every root class rather than a fixed set.
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element root : roundEnv.getRootElements()) {
            if (root.getKind() != ElementKind.CLASS) continue;
            TypeElement type = (TypeElement) root;
            try {
                generateCompanion(type);
            } catch (SkipGeneration skip) {
                note(type, "skipping " + type.getQualifiedName() + SUFFIX + " — "
                        + skip.getMessage() + " (startup scan will handle this class)");
            } catch (IOException | RuntimeException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Dirac companion generation failed, startup scan will handle it: " + e, type);
            }
        }
        if (roundEnv.processingOver()) {
            writeServicesFile();
        }
        return false;
    }

    private static final class SkipGeneration extends Exception {
        SkipGeneration(String message) {
            super(message);
        }
    }

    private record Spec(String name, String description, String unit, List<String> tags, String scope) {
    }

    private record GaugeModel(Spec spec, boolean staticMethod, String methodName) {
    }

    // ------------------------------------------------------------------
    // Model — mirrors DiracExtension scan semantics exactly
    // ------------------------------------------------------------------

    private void generateCompanion(TypeElement type) throws SkipGeneration, IOException {
        if (isInterceptor(type)) return;

        Timed classTimed = findTimed(type);
        Counted classCounted = findCounted(type);

        List<Spec> timers = new ArrayList<>();
        List<Spec> counters = new ArrayList<>();
        List<GaugeModel> gauges = new ArrayList<>();

        String binaryName = processingEnv.getElementUtils().getBinaryName(type).toString();
        String simpleName = type.getSimpleName().toString();
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();

        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            Gauge gauge = method.getAnnotation(Gauge.class);
            if (gauge != null) {
                gauges.add(gaugeModel(binaryName, method, gauge));
            }
            if (method.getModifiers().contains(Modifier.STATIC)) continue;
            boolean isPrivate = method.getModifiers().contains(Modifier.PRIVATE);

            Timed methodTimed = findTimed(method);
            Timed timed = methodTimed != null ? methodTimed : classTimed;
            if (timed != null && !(methodTimed == null && isPrivate)) {
                boolean classLevel = methodTimed == null;
                timers.add(new Spec(
                        resolveSimpleMetricName(binaryName, packageName, method.getSimpleName().toString(),
                                timed.name(), timed.absolute(), classLevel),
                        timed.description(), timed.unit(),
                        validatedTags(timed.tags()), normalizeScope(timed.scope())));
            }

            Counted methodCounted = findCounted(method);
            Counted counted = methodCounted != null ? methodCounted : classCounted;
            if (counted != null && !(methodCounted == null && isPrivate)) {
                boolean classLevel = methodCounted == null;
                counters.add(new Spec(
                        resolveSimpleMetricName(binaryName, packageName, method.getSimpleName().toString(),
                                counted.name(), counted.absolute(), classLevel),
                        counted.description(), counted.unit(),
                        validatedTags(counted.tags()), normalizeScope(counted.scope())));
            }
        }

        for (ExecutableElement ctor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (ctor.getModifiers().contains(Modifier.PRIVATE)) continue;
            Timed ctorTimed = findTimed(ctor);
            Timed timed = ctorTimed != null ? ctorTimed : classTimed;
            if (timed != null) {
                String name = ctorTimed == null
                        ? resolveClassLevelConstructorName(binaryName, packageName, simpleName,
                                timed.name(), timed.absolute())
                        : resolveConstructorLevelName(binaryName, simpleName, timed.name(), timed.absolute());
                timers.add(new Spec(name, timed.description(), timed.unit(),
                        validatedTags(timed.tags()), normalizeScope(timed.scope())));
            }
            Counted ctorCounted = findCounted(ctor);
            Counted counted = ctorCounted != null ? ctorCounted : classCounted;
            if (counted != null) {
                String name = ctorCounted == null
                        ? resolveClassLevelConstructorName(binaryName, packageName, simpleName,
                                counted.name(), counted.absolute())
                        : resolveConstructorLevelName(binaryName, simpleName, counted.name(), counted.absolute());
                counters.add(new Spec(name, counted.description(), counted.unit(),
                        validatedTags(counted.tags()), normalizeScope(counted.scope())));
            }
        }

        if (timers.isEmpty() && counters.isEmpty() && gauges.isEmpty()) {
            return; // not a metric bean — nothing to generate, nothing to skip
        }
        if (type.getNestingKind().isNested()) {
            throw new SkipGeneration("nested bean classes are not emitted as companions");
        }
        emit(type, binaryName, packageName, simpleName, timers, counters, gauges);
    }

    private GaugeModel gaugeModel(String binaryName, ExecutableElement method, Gauge gauge)
            throws SkipGeneration {
        if (method.getModifiers().contains(Modifier.PRIVATE)) {
            throw new SkipGeneration("@Gauge method " + method.getSimpleName()
                    + " is private — a source companion cannot call it");
        }
        if (!method.getParameters().isEmpty()) {
            throw new SkipGeneration("@Gauge method " + method.getSimpleName()
                    + " declares parameters (runtime rejects it at deployment)");
        }
        if (!isNumeric(method.getReturnType())) {
            throw new SkipGeneration("@Gauge method " + method.getSimpleName()
                    + " does not return a number (runtime rejects it at deployment)");
        }
        String explicit = gauge.name().trim();
        String metricName = explicit.isEmpty()
                ? (gauge.absolute() ? method.getSimpleName().toString()
                        : binaryName + "." + method.getSimpleName())
                : (gauge.absolute() ? explicit : binaryName + "." + explicit);
        return new GaugeModel(
                new Spec(metricName, gauge.description(), gauge.unit(),
                        validatedTags(gauge.tags()), normalizeScope(gauge.scope())),
                method.getModifiers().contains(Modifier.STATIC),
                method.getSimpleName().toString());
    }

    private boolean isNumeric(TypeMirror type) {
        return switch (type.getKind()) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            case DECLARED -> {
                TypeMirror number = processingEnv.getElementUtils()
                        .getTypeElement("java.lang.Number").asType();
                yield processingEnv.getTypeUtils().isAssignable(type, number);
            }
            default -> false;
        };
    }

    /** Same naming rules as DiracExtension#resolveSimpleMetricName. */
    private static String resolveSimpleMetricName(String binaryName, String packageName, String methodName,
                                                  String name, boolean absolute, boolean classLevel) {
        String explicit = name == null ? "" : name.trim();
        if (!explicit.isEmpty()) {
            if (absolute) return explicit;
            if (classLevel) {
                return packageName.isEmpty() ? explicit + "." + methodName
                        : packageName + "." + explicit + "." + methodName;
            }
            return binaryName + "." + explicit;
        }
        return absolute ? methodName : binaryName + "." + methodName;
    }

    private static String resolveClassLevelConstructorName(String binaryName, String packageName,
                                                           String simpleName, String name, boolean absolute) {
        String explicit = name == null ? "" : name.trim();
        if (!explicit.isEmpty()) {
            if (absolute) return explicit + "." + simpleName;
            return packageName.isEmpty() ? explicit + "." + simpleName
                    : packageName + "." + explicit + "." + simpleName;
        }
        return binaryName + "." + simpleName;
    }

    private static String resolveConstructorLevelName(String binaryName, String simpleName,
                                                      String name, boolean absolute) {
        String explicit = name == null ? "" : name.trim();
        if (!explicit.isEmpty()) {
            return absolute ? explicit : binaryName + "." + explicit;
        }
        return absolute ? simpleName : binaryName + "." + simpleName;
    }

    private static String normalizeScope(String scope) {
        String normalized = scope == null ? "application" : scope.trim();
        return normalized.isEmpty() ? "application" : normalized;
    }

    /** Tag validation mirroring DiracExtension#parseTag — invalid tags trip the valve. */
    private List<String> validatedTags(String[] tags) throws SkipGeneration {
        if (tags == null || tags.length == 0) return List.of();
        List<String> out = new ArrayList<>(tags.length);
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            int separator = tag.indexOf('=');
            if (separator <= 0 || separator == tag.length() - 1) {
                throw new SkipGeneration("malformed tag '" + tag + "' (runtime rejects it at deployment)");
            }
            String key = tag.substring(0, separator).trim();
            if ("mp_scope".equalsIgnoreCase(key) || "mp_app".equalsIgnoreCase(key)) {
                throw new SkipGeneration("reserved tag '" + key + "' (runtime rejects it at deployment)");
            }
            out.add(tag);
        }
        return out;
    }

    private Timed findTimed(Element element) {
        Timed direct = element.getAnnotation(Timed.class);
        if (direct != null) return direct;
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Timed meta = mirror.getAnnotationType().asElement().getAnnotation(Timed.class);
            if (meta != null) return meta;
        }
        return null;
    }

    private Counted findCounted(Element element) {
        Counted direct = element.getAnnotation(Counted.class);
        if (direct != null) return direct;
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Counted meta = mirror.getAnnotationType().asElement().getAnnotation(Counted.class);
            if (meta != null) return meta;
        }
        return null;
    }

    private boolean isInterceptor(TypeElement type) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement())
                    .getQualifiedName().contentEquals(INTERCEPTOR_FQN)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Emission
    // ------------------------------------------------------------------

    private void emit(TypeElement type, String binaryName, String packageName, String simpleName,
                      List<Spec> timers, List<Spec> counters, List<GaugeModel> gauges)
            throws IOException {
        String className = simpleName + SUFFIX;
        String generatedFqn = packageName.isEmpty() ? className : packageName + "." + className;
        String beanSource = type.getQualifiedName().toString();

        StringBuilder out = new StringBuilder(4096);
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("// Generated by io.vidocq.dirac.processor.DiracMetricsProcessor — do not edit.\n");
        out.append("public final class ").append(className)
                .append(" implements io.vidocq.dirac.spi.gen.MetricsCompanion {\n\n");
        out.append("    @java.lang.Override\n    public java.lang.Class<?> beanClass() {\n")
                .append("        return ").append(beanSource).append(".class;\n    }\n\n");

        emitSpecList(out, "timers", timers);
        emitSpecList(out, "counters", counters);

        out.append("    @java.lang.Override\n")
                .append("    public java.util.List<GaugeSpec> gauges() {\n")
                .append("        return java.util.List.of(");
        for (int i = 0; i < gauges.size(); i++) {
            GaugeModel g = gauges.get(i);
            if (i > 0) out.append(",");
            out.append("\n                new GaugeSpec(")
                    .append(lit(g.spec().name())).append(", ")
                    .append(lit(g.spec().description())).append(", ")
                    .append(lit(g.spec().unit())).append(", ")
                    .append(stringList(g.spec().tags())).append(", ")
                    .append(lit(g.spec().scope())).append(",\n                        ");
            if (g.staticMethod()) {
                out.append("true, ignored -> ").append(beanSource).append(".")
                        .append(g.methodName()).append("())");
            } else {
                out.append("false, bean -> ((").append(beanSource).append(") bean).")
                        .append(g.methodName()).append("())");
            }
        }
        out.append(gauges.isEmpty() ? ");\n" : ");\n").append("    }\n");
        out.append("}\n");

        try {
            var file = processingEnv.getFiler().createSourceFile(generatedFqn, type);
            try (Writer w = file.openWriter()) {
                w.write(out.toString());
            }
        } catch (FilerException e) {
            note(type, "skipping duplicate generation of " + generatedFqn + ": " + e.getMessage());
            return;
        }
        companionFqns.add(generatedFqn);
    }

    private void emitSpecList(StringBuilder out, String methodName, List<Spec> specs) {
        out.append("    @java.lang.Override\n")
                .append("    public java.util.List<MetricSpec> ").append(methodName).append("() {\n")
                .append("        return java.util.List.of(");
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            if (i > 0) out.append(",");
            out.append("\n                new MetricSpec(")
                    .append(lit(s.name())).append(", ")
                    .append(lit(s.description())).append(", ")
                    .append(lit(s.unit())).append(", ")
                    .append(stringList(s.tags())).append(", ")
                    .append(lit(s.scope())).append(")");
        }
        out.append(");\n    }\n\n");
    }

    private static String stringList(List<String> values) {
        StringBuilder sb = new StringBuilder("java.util.List.of(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(lit(values.get(i)));
        }
        return sb.append(")").toString();
    }

    /** Java string literal (escaped) for emission into generated source. */
    private static String lit(String value) {
        var sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    private void writeServicesFile() {
        if (servicesWritten || companionFqns.isEmpty()) return;
        servicesWritten = true;
        try {
            var resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/io.vidocq.dirac.spi.gen.MetricsCompanion");
            try (Writer w = resource.openWriter()) {
                for (String fqn : companionFqns) {
                    w.write(fqn);
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not write MetricsCompanion services file (naming-convention "
                            + "resolution still applies): " + e);
        }
    }

    private void note(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "[dirac-processor] " + message, element);
    }
}
