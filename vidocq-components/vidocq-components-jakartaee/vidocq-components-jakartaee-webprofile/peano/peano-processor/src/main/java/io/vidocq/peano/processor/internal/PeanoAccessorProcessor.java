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
package io.vidocq.peano.processor.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import io.vidocq.peano.annotation.ELAccessible;

/**
 * Per-type generation for selected roots and their current-compilation descendants.
 * Wildcard activation permits explicit selection when no annotation is present;
 * unrelated annotations are never claimed or scanned.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions("peano.accessorTypes")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class PeanoAccessorProcessor extends AbstractProcessor {
    private static final String SUFFIX = "__PeanoAccessors";
    private final Set<String> emitted = new HashSet<>();
    private final Set<ModuleElement> applicationModules = new HashSet<>();
    private final Set<String> pendingTypes = new java.util.LinkedHashSet<>();
    private final java.util.Map<String, TypeElement> sourceTypes = new TreeMap<>();
    private final java.util.Map<String, TypeElement> selectedRoots = new TreeMap<>();
    private final java.util.Map<String, TypeElement> deferredTypes = new TreeMap<>();
    private boolean optionsProcessed;

    private record Property(String name, ExecutableElement getter, ExecutableElement setter) {}

    public PeanoAccessorProcessor() {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            pendingTypes.forEach(name -> error("Unknown accessor type: " + name, null));
            deferredTypes.forEach((name, type) -> error("Unresolved accessor hierarchy: " + name, type));
            return false;
        }
        for (Element root : round.getRootElements()) {
            ModuleElement module = processingEnv.getElementUtils().getModuleOf(root);
            if (module != null) applicationModules.add(module);
            rememberSourceTypes(root);
        }
        if (applicationModules.size() > 1) {
            error("Compile one application module at a time for property accessor generation", null);
            return false;
        }
        var selected = new ArrayList<TypeElement>();
        for (Element element : round.getElementsAnnotatedWith(ELAccessible.class)) {
            if (element instanceof TypeElement type) selected.add(type);
        }
        if (!optionsProcessed) {
            optionsProcessed = true;
            String configured = processingEnv.getOptions().getOrDefault("peano.accessorTypes", "");
            for (String name : configured.split(",")) {
                if (name.isBlank()) continue;
                pendingTypes.add(name.trim());
            }
        }
        var pending = pendingTypes.iterator();
        while (pending.hasNext()) {
            TypeElement type = processingEnv.getElementUtils().getTypeElement(pending.next());
            if (type != null) {
                selected.add(type);
                pending.remove();
            }
        }
        selected.stream().sorted(java.util.Comparator.comparing(t -> t.getQualifiedName().toString()))
                .forEach(type -> {
                    if (eligibleSubtype(type)) selectedRoots.put(type.getQualifiedName().toString(), type);
                    generate(type);
                });
        for (TypeElement type : new ArrayList<>(deferredTypes.values())) generate(type);
        for (TypeElement candidate : sourceTypes.values()) {
            if (!eligibleSubtype(candidate) || unresolvedHierarchy(candidate.asType(), new HashSet<>())) continue;
            boolean selectedFamily = selectedRoots.values().stream().anyMatch(root ->
                    !unresolvedHierarchy(root.asType(), new HashSet<>())
                    && processingEnv.getTypeUtils().isSubtype(processingEnv.getTypeUtils().erasure(candidate.asType()),
                            processingEnv.getTypeUtils().erasure(root.asType())));
            if (selectedFamily) generate(candidate);
        }
        // Do not consume annotations belonging to other processors.
        return false;
    }

    private void rememberSourceTypes(Element element) {
        if (!(element instanceof TypeElement type)) return;
        sourceTypes.put(type.getQualifiedName().toString(), type);
        ElementFilter.typesIn(type.getEnclosedElements()).forEach(this::rememberSourceTypes);
    }

    private boolean eligibleSubtype(TypeElement type) {
        ModuleElement module = processingEnv.getElementUtils().getModuleOf(type);
        return (type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.RECORD)
                && module != null && !module.isUnnamed() && applicationModules.contains(module)
                && !privateEnclosing(type);
    }

    private void generate(TypeElement type) {
        ModuleElement module = processingEnv.getElementUtils().getModuleOf(type);
        String key = module + "/" + type.getQualifiedName();
        if (emitted.contains(key)) return;
        if (eligibleSubtype(type) && unresolvedHierarchy(type.asType(), new HashSet<>())) {
            deferredTypes.put(type.getQualifiedName().toString(), type);
            return;
        }
        deferredTypes.remove(type.getQualifiedName().toString());
        emitted.add(key);
        if (module == null || module.isUnnamed() || !applicationModules.contains(module)) {
            error("Accessor type must belong to the current application module: " + type.getQualifiedName(), type);
            return;
        }
        if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
            error("Only classes and records can declare property accessors", type);
            return;
        }
        if (privateEnclosing(type)) {
            error("Accessor type has a private enclosing declaration and cannot be accessed by a package table", type);
            return;
        }
        List<Property> properties = properties(type);
        if (properties == null) return;
        String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String binary = processingEnv.getElementUtils().getBinaryName(type).toString();
        String simple = binary.substring(pkg.length() + 1) + SUFFIX;
        String provider = pkg + "." + simple;
        try {
            var source = processingEnv.getFiler().createSourceFile(module.getQualifiedName() + "/" + provider, type);
            try (var writer = source.openWriter()) {
                writer.write(source(type, pkg, simple, properties));
            }
            var fragment = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT,
                    "", "META-INF/peano/accessor-providers/" + provider + ".txt", type);
            try (var writer = fragment.openWriter()) {
                writer.write("provides io.vidocq.peano.spi.AccessorProvider with " + provider + ";\n");
            }
        } catch (IOException exception) {
            error("Cannot generate accessor provider " + provider + ": " + exception.getMessage(), type);
        }
    }

    private boolean unresolvedHierarchy(TypeMirror mirror, Set<String> visited) {
        if (mirror.getKind() == TypeKind.ERROR) return true;
        if (!(processingEnv.getTypeUtils().asElement(mirror) instanceof TypeElement type)
                || !visited.add(type.getQualifiedName().toString())) return false;
        return unresolvedHierarchy(type.getSuperclass(), visited)
                || type.getInterfaces().stream().anyMatch(parent -> unresolvedHierarchy(parent, visited));
    }

    private List<Property> properties(TypeElement type) {
        if (type.getKind() == ElementKind.RECORD) {
            return type.getRecordComponents().stream()
                    .map(component -> new Property(component.getSimpleName().toString(), component.getAccessor(), null))
                    .sorted(java.util.Comparator.comparing(Property::name)).toList();
        }
        var readers = new TreeMap<String, ExecutableElement>();
        var writers = new TreeMap<String, List<ExecutableElement>>();
        for (ExecutableElement method : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))) {
            if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)) continue;
            String name = method.getSimpleName().toString();
            String property = null;
            if (method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID) {
                boolean is = name.startsWith("is") && name.length() > 2 && method.getReturnType().getKind() == TypeKind.BOOLEAN;
                if (is) property = decapitalize(name.substring(2));
                else if (name.startsWith("get") && name.length() > 3) property = decapitalize(name.substring(3));
                if (property != null) {
                    ExecutableElement previous = readers.get(property);
                    boolean previousIs = previous != null && previous.getSimpleName().toString().startsWith("is")
                            && previous.getReturnType().getKind() == TypeKind.BOOLEAN;
                    if (previous == null || is || !previousIs && processingEnv.getTypeUtils().isSubtype(
                            method.getReturnType(), previous.getReturnType())) readers.put(property, method);
                }
            }
            if (name.startsWith("set") && name.length() > 3 && method.getParameters().size() == 1
                    && method.getReturnType().getKind() == TypeKind.VOID) {
                writers.computeIfAbsent(decapitalize(name.substring(3)), ignored -> new ArrayList<>()).add(method);
            }
        }
        var names = new java.util.TreeSet<>(readers.keySet());
        names.addAll(writers.keySet());
        var result = new ArrayList<Property>();
        boolean valid = true;
        for (String name : names) {
            ExecutableElement getter = readers.get(name);
            List<ExecutableElement> setters = writers.getOrDefault(name, List.of());
            if (getter != null) {
                TypeMirror expected = processingEnv.getTypeUtils().erasure(getter.getReturnType());
                setters = setters.stream().filter(setter -> processingEnv.getTypeUtils().isSameType(expected,
                        processingEnv.getTypeUtils().erasure(setter.getParameters().getFirst().asType()))).toList();
            }
            if (setters.size() > 1) {
                error("Ambiguous setter for property " + name, type);
                valid = false;
                continue;
            }
            ExecutableElement setter = setters.isEmpty() ? null : setters.getFirst();
            if (getter != null && typeVariable(getter.getReturnType())
                    || setter != null && typeVariable(setter.getParameters().getFirst().asType())) {
                error("Type-variable property adapters are not supported yet: " + name, type);
                valid = false;
                continue;
            }
            result.add(new Property(name, getter, setter));
        }
        return valid ? result : null;
    }

    private static boolean typeVariable(TypeMirror type) {
        return type.getKind() == TypeKind.TYPEVAR
                || type instanceof ArrayType array && typeVariable(array.getComponentType());
    }

    private static boolean privateEnclosing(Element element) {
        for (Element current = element; current instanceof TypeElement; current = current.getEnclosingElement()) {
            if (current.getModifiers().contains(Modifier.PRIVATE)) return true;
        }
        return false;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private String source(TypeElement type, String pkg, String simple, List<Property> properties) throws IOException {
        String model = type.getQualifiedName().toString();
        String header;
        try (var input = PeanoAccessorProcessor.class.getResourceAsStream("license-header.txt")) {
            if (input == null) throw new IOException("Missing generated-source license header");
            header = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var text = new StringBuilder(header).append("// Generated by PeanoAccessorProcessor. Do not edit.\n")
                .append("package ").append(pkg).append(";\n")
                .append("@java.lang.SuppressWarnings({\"rawtypes\", \"unchecked\"})\n")
                .append("public final class ").append(simple).append(" implements io.vidocq.peano.spi.AccessorProvider {\n")
                .append("  public ").append(simple).append("() {}\n")
                .append("  public java.util.Collection<? extends io.vidocq.peano.spi.AccessorTable> accessors() { return java.util.List.of(new Table()); }\n")
                .append("  private static final class Table extends io.vidocq.peano.spi.AccessorTable {\n")
                .append("    public java.lang.Class<?> targetType() { return ").append(model).append(".class; }\n")
                .append("    private boolean accepts(jakarta.el.ELContext context, java.lang.Object base, java.lang.Object property) {\n")
                .append("      java.util.Objects.requireNonNull(context); return base != null && base.getClass() == ")
                .append(model).append(".class && property instanceof java.lang.String;\n    }\n");
        operation(text, model, properties, "getValue");
        operation(text, model, properties, "getType");
        operation(text, model, properties, "isReadOnly");
        operation(text, model, properties, "setValue");
        new MethodGenerator(processingEnv).append(text, type);
        text.append("    public java.lang.Class<?> getCommonPropertyType(jakarta.el.ELContext context, java.lang.Object base) {\n")
                .append("      java.util.Objects.requireNonNull(context); return base != null && base.getClass() == ")
                .append(model).append(".class ? java.lang.String.class : null;\n    }\n")
                .append("  }\n}\n");
        return text.toString();
    }

    private void operation(StringBuilder text, String model, List<Property> properties, String operation) {
        boolean write = operation.equals("setValue");
        String returnType = switch (operation) {
            case "getType" -> "java.lang.Class<?>";
            case "isReadOnly" -> "boolean";
            case "setValue" -> "void";
            default -> "java.lang.Object";
        };
        String fallback = write ? "return;" : operation.equals("isReadOnly") ? "return false;" : "return null;";
        text.append("    public ").append(returnType).append(' ').append(operation)
                .append("(jakarta.el.ELContext context, java.lang.Object base, java.lang.Object property")
                .append(write ? ", java.lang.Object value" : "").append(") {\n")
                .append("      if (!accepts(context, base, property)) { ").append(fallback).append(" }\n")
                .append("      try { switch ((java.lang.String) property) {\n");
        for (Property property : properties) {
            text.append("        case \"").append(property.name()).append("\":\n")
                    .append("          context.setPropertyResolved(base, property);\n");
            switch (operation) {
                case "getValue" -> {
                    if (property.getter() == null) text.append("          throw new jakarta.el.PropertyNotFoundException(\"Write-only property\");\n");
                    else text.append("          return ((").append(model).append(") base).").append(property.getter().getSimpleName()).append("();\n");
                }
                case "getType" -> text.append("          return ").append(property.setter() == null ? "null"
                        : processingEnv.getTypeUtils().erasure(property.setter().getParameters().getFirst().asType()) + ".class").append(";\n");
                case "isReadOnly" -> text.append("          return ").append(property.setter() == null).append(";\n");
                case "setValue" -> {
                    if (property.setter() == null) text.append("          throw new jakarta.el.PropertyNotWritableException(\"Read-only property\");\n");
                    else text.append("          ((").append(model).append(") base).").append(property.setter().getSimpleName())
                            .append("(context.convertToType(value, ")
                            .append(processingEnv.getTypeUtils().erasure(property.setter().getParameters().getFirst().asType()))
                            .append(".class)); return;\n");
                }
                default -> throw new IllegalArgumentException(operation);
            }
        }
        text.append("        default: ").append(fallback).append("\n")
                .append("      } } catch (jakarta.el.ELException failure) { throw failure; }\n")
                .append("      catch (java.lang.Throwable failure) { throw new jakarta.el.ELException(\"Generated property operation failed\", failure); }\n")
                .append("    }\n");
    }

    private void error(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}

