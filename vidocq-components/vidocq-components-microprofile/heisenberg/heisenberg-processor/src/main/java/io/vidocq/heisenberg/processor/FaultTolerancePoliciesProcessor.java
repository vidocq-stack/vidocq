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
package io.vidocq.heisenberg.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Generates the {@code <Bean>$$HeisenbergPolicies} companion of each class whose methods carry MicroProfile Fault
 * Tolerance annotations, directly or through a class of their hierarchy (vidocq-workspace#38). The companion holds
 * the annotations as constant {@code Literals}, resolved with the rule of the runtime's {@code AnnotationReader}:
 * each annotation from the method's nearest declaration in the class hierarchy, else from the nearest class carrying
 * it. The runtime then needs no reflection on the annotations, which matters for ahead-of-time compilation.
 *
 * <p>A class the processor cannot serve is skipped with a compiler note and read by reflection at run time: a class
 * the companion cannot name (private, local, anonymous), or annotation values naming a class it cannot reach.</p>
 */
public final class FaultTolerancePoliciesProcessor extends AbstractProcessor {

    private static final String SUFFIX = "$$HeisenbergPolicies";
    private static final String COMPANION = "io.vidocq.heisenberg.spi.gen.FaultToleranceCompanion";
    private static final String LITERALS = "io.vidocq.heisenberg.spi.gen.Literals";
    private static final String FT = "org.eclipse.microprofile.faulttolerance.";

    /** The six annotations, each with its members in the order of its literal's components. */
    private static final Map<String, List<String>> ANNOTATIONS = new LinkedHashMap<>();

    static {
        ANNOTATIONS.put("Retry", List.of("maxRetries", "delay", "delayUnit", "maxDuration", "durationUnit", "jitter",
                "jitterDelayUnit", "retryOn", "abortOn"));
        ANNOTATIONS.put("Timeout", List.of("value", "unit"));
        ANNOTATIONS.put("CircuitBreaker", List.of("failOn", "skipOn", "delay", "delayUnit", "requestVolumeThreshold",
                "failureRatio", "successThreshold"));
        ANNOTATIONS.put("Bulkhead", List.of("value", "waitingTaskQueue"));
        ANNOTATIONS.put("Fallback", List.of("value", "fallbackMethod", "applyOn", "skipOn"));
        ANNOTATIONS.put("Asynchronous", List.of());
    }

    private final Set<String> companions = new TreeSet<>();
    private final Set<String> seen = new TreeSet<>();

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // A class inherits its policies from its superclasses: every class is inspected, annotated or not.
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement type : ElementFilter.typesIn(roundEnv.getRootElements())) {
            visit(type);
        }
        if (roundEnv.processingOver()) {
            writeServicesFile();
        }
        return false;
    }

    private void visit(TypeElement type) {
        if (type.getKind() == ElementKind.CLASS && seen.add(binaryName(type))) {
            try {
                generate(type);
            } catch (Skip skip) {
                note(type, "no " + binaryName(type) + SUFFIX + ": " + skip.getMessage()
                        + "; the runtime reads its Fault Tolerance annotations by reflection");
            } catch (IOException | RuntimeException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Heisenberg policies not generated, the runtime reads them by reflection: " + e, type);
            }
        }
        for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
            visit(nested);
        }
    }

    private static final class Skip extends Exception {
        Skip(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Resolution: the runtime AnnotationReader's rule, on the language model
    // ------------------------------------------------------------------

    private void generate(TypeElement type) throws Skip, IOException {
        if (type.getModifiers().contains(Modifier.ABSTRACT) || isGenerated(type)) {
            return;
        }
        List<TypeElement> chain = classChain(type);
        Map<String, AnnotationMirror> classLevel = new LinkedHashMap<>();
        for (String name : ANNOTATIONS.keySet()) {
            for (TypeElement c : chain) {
                AnnotationMirror found = mirror(c, name);
                if (found != null) {
                    classLevel.put(name, found);
                    break;
                }
            }
        }
        Map<String, Map<String, AnnotationMirror>> byMethod = new LinkedHashMap<>();
        Set<String> keys = new TreeSet<>();
        for (TypeElement c : chain) {
            for (ExecutableElement method : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (method.getModifiers().contains(Modifier.STATIC)
                        || method.getModifiers().contains(Modifier.PRIVATE)) {
                    continue;
                }
                String key = methodKey(method);
                if (!keys.add(key)) {
                    continue; // a nearer declaration of the same method was seen first
                }
                Map<String, AnnotationMirror> resolved = new LinkedHashMap<>();
                for (String name : ANNOTATIONS.keySet()) {
                    AnnotationMirror found = mirror(method, name);
                    if (found == null) found = classLevel.get(name);
                    if (found != null) resolved.put(name, found);
                }
                if (!resolved.isEmpty()) byMethod.put(key, resolved);
            }
        }
        if (byMethod.isEmpty()) {
            return;
        }
        if (!nameable(type)) {
            throw new Skip("the class cannot be named from its package");
        }
        emit(type, byMethod);
    }

    /** The class and its superclasses, nearest first, {@code Object} excluded. */
    private List<TypeElement> classChain(TypeElement type) {
        List<TypeElement> chain = new ArrayList<>();
        TypeElement current = type;
        while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
            chain.add(current);
            TypeMirror superclass = current.getSuperclass();
            current = superclass.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superclass).asElement() : null;
        }
        return chain;
    }

    private static AnnotationMirror mirror(Element element, String simpleName) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            TypeElement annotation = (TypeElement) m.getAnnotationType().asElement();
            if (annotation.getQualifiedName().contentEquals(FT + simpleName)) {
                return m;
            }
        }
        return null;
    }

    /** Classes another processor generated, such as Vauban's client proxies, inherit the policies of their bean. */
    private static boolean isGenerated(TypeElement type) {
        for (AnnotationMirror m : type.getAnnotationMirrors()) {
            String name = ((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().toString();
            if (name.endsWith(".Generated")) return true;
        }
        String simple = type.getSimpleName().toString();
        return simple.contains("$$") || simple.endsWith("_ClientProxy") || simple.endsWith("_Subclass");
    }

    /** Whether {@code Type.class} compiles in the class's package: no private, local or anonymous class on the way. */
    private static boolean nameable(TypeElement type) {
        Element current = type;
        while (current instanceof TypeElement t) {
            if (t.getModifiers().contains(Modifier.PRIVATE) || t.getNestingKind() == NestingKind.LOCAL
                    || t.getNestingKind() == NestingKind.ANONYMOUS) {
                return false;
            }
            current = t.getEnclosingElement();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Keys: FaultToleranceCompanion.methodKey on the language model
    // ------------------------------------------------------------------

    private String methodKey(ExecutableElement method) {
        StringBuilder key = new StringBuilder(method.getSimpleName()).append('(');
        List<? extends VariableElement> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) key.append(',');
            key.append(typeName(processingEnv.getTypeUtils().erasure(parameters.get(i).asType())));
        }
        return key.append(')').toString();
    }

    /** As {@link Class#getTypeName()} writes the erased type. */
    private String typeName(TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> typeName(((ArrayType) type).getComponentType()) + "[]";
            case DECLARED -> binaryName((TypeElement) ((DeclaredType) type).asElement());
            default -> type.toString();
        };
    }

    private String binaryName(TypeElement type) {
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    // ------------------------------------------------------------------
    // Emission
    // ------------------------------------------------------------------

    private void emit(TypeElement type, Map<String, Map<String, AnnotationMirror>> byMethod)
            throws Skip, IOException {
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String binary = binaryName(type);
        String simpleCompanion = binary.substring(packageName.isEmpty() ? 0 : packageName.length() + 1) + SUFFIX;
        List<String> fields = new ArrayList<>();
        List<String> cases = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Map<String, AnnotationMirror>> entry : byMethod.entrySet()) {
            String field = "P" + index++;
            StringBuilder arguments = new StringBuilder();
            for (String name : ANNOTATIONS.keySet()) {
                if (!arguments.isEmpty()) arguments.append(",\n                ");
                AnnotationMirror mirror = entry.getValue().get(name);
                arguments.append(mirror == null ? "null" : literal(name, mirror, packageName));
            }
            fields.add("    private static final " + COMPANION + ".Policies " + field + " = new " + COMPANION
                    + ".Policies(\n                " + arguments + ");");
            cases.add("            case " + processingEnv.getElementUtils().getConstantExpression(entry.getKey())
                    + " -> " + field + ";");
        }
        JavaFileObject file = processingEnv.getFiler().createSourceFile(
                packageName.isEmpty() ? simpleCompanion : packageName + "." + simpleCompanion, type);
        try (Writer w = file.openWriter()) {
            if (!packageName.isEmpty()) w.write("package " + packageName + ";\n\n");
            // No @Generated: javax.annotation.processing is in java.compiler, which an application module does not read.
            w.write("/** Fault Tolerance policies of {@code " + type.getQualifiedName()
                    + "}, generated by " + getClass().getName() + ". */\n");
            w.write("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            w.write("public final class " + simpleCompanion + " implements " + COMPANION + " {\n\n");
            for (String f : fields) w.write(f + "\n\n");
            w.write("    @Override\n    public Class<?> beanClass() {\n        return "
                    + type.getQualifiedName() + ".class;\n    }\n\n");
            w.write("    @Override\n    public " + COMPANION + ".Policies policies(String key) {\n"
                    + "        return switch (key) {\n");
            for (String c : cases) w.write(c + "\n");
            w.write("            default -> null;\n        };\n    }\n}\n");
        }
        companions.add(packageName.isEmpty() ? simpleCompanion : packageName + "." + simpleCompanion);
    }

    private String literal(String annotation, AnnotationMirror mirror, String packageName) throws Skip {
        if ("Asynchronous".equals(annotation)) {
            return LITERALS + ".AsynchronousLiteral.INSTANCE";
        }
        Map<String, AnnotationValue> values = new LinkedHashMap<>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(mirror)
                .forEach((member, value) -> values.put(member.getSimpleName().toString(), value));
        StringBuilder out = new StringBuilder("new " + LITERALS + "." + annotation + "Literal(");
        List<String> members = ANNOTATIONS.get(annotation);
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) out.append(", ");
            AnnotationValue value = values.get(members.get(i));
            if (value == null) {
                throw new Skip("@" + annotation + " has no value for " + members.get(i));
            }
            out.append(value(value.getValue(), packageName));
        }
        return out.append(')').toString();
    }

    private String value(Object raw, String packageName) throws Skip {
        if (raw instanceof Long l) return l + "L";
        if (raw instanceof Double d) return Double.isFinite(d) ? d + "d" : "Double." + (d > 0 ? "POSITIVE_INFINITY" : "NEGATIVE_INFINITY");
        if (raw instanceof Integer || raw instanceof Boolean) return raw.toString();
        if (raw instanceof String s) return processingEnv.getElementUtils().getConstantExpression(s);
        if (raw instanceof VariableElement constant) {
            return ((TypeElement) constant.getEnclosingElement()).getQualifiedName() + "." + constant.getSimpleName();
        }
        if (raw instanceof TypeMirror type) return classLiteral(type, packageName);
        if (raw instanceof List<?> list) {
            StringBuilder out = new StringBuilder("new Class[] {");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(value(((AnnotationValue) list.get(i)).getValue(), packageName));
            }
            return out.append('}').toString();
        }
        throw new Skip("unexpected annotation value " + raw);
    }

    /** {@code Type.class}, when the companion's package can name it. */
    private String classLiteral(TypeMirror type, String packageName) throws Skip {
        TypeMirror erased = processingEnv.getTypeUtils().erasure(type);
        if (erased.getKind() != TypeKind.DECLARED) {
            throw new Skip("unexpected class value " + type);
        }
        TypeElement element = (TypeElement) ((DeclaredType) erased).asElement();
        String elementPackage = processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
        Element current = element;
        while (current instanceof TypeElement t) {
            Set<Modifier> modifiers = t.getModifiers();
            boolean reachable = modifiers.contains(Modifier.PUBLIC)
                    || (!modifiers.contains(Modifier.PRIVATE) && elementPackage.equals(packageName));
            if (!reachable || t.getNestingKind() == NestingKind.LOCAL || t.getNestingKind() == NestingKind.ANONYMOUS) {
                throw new Skip(element.getQualifiedName() + " cannot be named from package " + packageName);
            }
            current = t.getEnclosingElement();
        }
        return element.getQualifiedName() + ".class";
    }

    private void writeServicesFile() {
        if (companions.isEmpty()) return;
        try {
            FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/" + COMPANION);
            try (Writer w = file.openWriter()) {
                for (String companion : companions) w.write(companion + "\n");
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Heisenberg could not write META-INF/services/" + COMPANION
                            + "; companions are still found by their name: " + e);
        }
    }

    private void note(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[Heisenberg] " + message, element);
    }
}
