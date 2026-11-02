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
package io.vidocq.ravel.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates, for an application, the code Ravel otherwise runs by reflection:
 * <ul>
 *   <li>for each {@code @ConfigProperties} class, a factory in the class's package that creates the instance and
 *       sets its fields (MicroProfile Config 3.1 §6.4), registered as {@code io.vidocq.ravel.spi.GeneratedConfigProperties};</li>
 *   <li>for the types injected with {@code @ConfigProperty} or held by a {@code @ConfigProperties} field, the
 *       implicit converter the specification picks (§5.2: {@code of(String)}, {@code valueOf(String)},
 *       {@code parse(CharSequence)}, a {@code String} constructor, an enum's {@code valueOf}), called directly and
 *       registered as {@code io.vidocq.ravel.spi.GeneratedConverters}.</li>
 * </ul>
 * A class or a type the generated code cannot handle with plain calls is left to Ravel's reflective path.
 */
@SupportedAnnotationTypes({
        RavelProcessor.CONFIG_PROPERTIES,
        RavelProcessor.CONFIG_PROPERTY})
public final class RavelProcessor extends AbstractProcessor {

    static final String CONFIG_PROPERTIES = "org.eclipse.microprofile.config.inject.ConfigProperties";
    static final String CONFIG_PROPERTY = "org.eclipse.microprofile.config.inject.ConfigProperty";
    private static final String UNCONFIGURED_PREFIX = "org.eclipse.microprofile.config.inject.configproperties.unconfiguredprefix";
    private static final String UNCONFIGURED_VALUE = "org.eclipse.microprofile.config.configproperty.unconfigureddvalue";
    private static final String FACTORIES_SERVICE = "META-INF/services/io.vidocq.ravel.spi.GeneratedConfigProperties";
    private static final String CONVERTERS_SERVICE = "META-INF/services/io.vidocq.ravel.spi.GeneratedConverters";
    private static final String CONVERTERS_CLASS = "_RavelConverters";

    /** Types Ravel converts with its built-in converters: no implicit converter is generated for them. */
    private static final Set<String> BUILT_IN = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Class",
            "java.lang.Object");

    private static final Set<String> WRAPPERS = Set.of(
            "java.util.Optional", "java.util.List", "java.util.Set", "java.util.function.Supplier",
            "jakarta.inject.Provider");

    private final Set<String> factories = new LinkedHashSet<>();
    private final Map<String, TypeElement> converterTypes = new TreeMap<>();
    private String converterPackage;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            writeConverters();
            writeServices();
            return false;
        }
        TypeElement configProperties = processingEnv.getElementUtils().getTypeElement(CONFIG_PROPERTIES);
        TypeElement configProperty = processingEnv.getElementUtils().getTypeElement(CONFIG_PROPERTY);
        if (configProperties != null) {
            for (Element element : round.getElementsAnnotatedWith(configProperties)) {
                if (element.getKind() == ElementKind.CLASS) {
                    TypeElement type = (TypeElement) element;
                    for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
                        if (!field.getModifiers().contains(Modifier.STATIC)) {
                            collectConverterType(field.asType(), type);
                        }
                    }
                    generateFactory(type);
                }
            }
        }
        if (configProperty != null) {
            for (Element element : round.getElementsAnnotatedWith(configProperty)) {
                collectConverterType(element.asType(), element);
            }
        }
        return false;
    }

    // ---- @ConfigProperties factories ----

    private void generateFactory(TypeElement type) {
        String reason = unsupported(type);
        if (reason != null) {
            note(type, "Ravel: " + type.getQualifiedName() + " keeps the reflective @ConfigProperties path: " + reason);
            return;
        }
        String pkg = packageOf(type);
        String binary = processingEnv.getElementUtils().getBinaryName(type).toString();
        String simple = binary.substring(pkg.isEmpty() ? 0 : pkg.length() + 1).replace('$', '_') + "$$RavelConfigProperties";
        String source = type.getQualifiedName().toString();

        var fields = new StringBuilder();
        var handles = new StringBuilder();
        for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            boolean viaHandle = field.getModifiers().contains(Modifier.PRIVATE);
            String name = field.getSimpleName().toString();
            if (viaHandle) {
                handles.append("    private static final java.lang.invoke.VarHandle ").append(name.toUpperCase())
                        .append("_HANDLE = handle(\"").append(name).append("\", ")
                        .append(erasure(field.asType())).append(".class);\n");
            }
            fields.append(fieldCode(field, name, viaHandle));
        }

        String code = "package " + pkg + ";\n\n"
                + "/** Generated by Ravel's processor: fills {@link " + source + "} without reflection. */\n"
                + "@javax.annotation.processing.Generated(\"io.vidocq.ravel.processor.RavelProcessor\")\n"
                + "public final class " + simple + " implements io.vidocq.ravel.spi.GeneratedConfigProperties {\n\n"
                + handles
                + "\n    @Override\n    public String typeName() {\n        return \"" + binary + "\";\n    }\n\n"
                + "    @Override\n    public String classPrefix() {\n        return \"" + escape(classPrefix(type)) + "\";\n    }\n\n"
                + "    @Override\n    public Object create(String prefix, org.eclipse.microprofile.config.Config config) {\n"
                + "        " + source + " instance = new " + source + "();\n"
                + fields
                + "        return instance;\n    }\n\n"
                + "    private static String name(String prefix, String property) {\n"
                + "        return prefix.isEmpty() ? property : prefix + \".\" + property;\n    }\n"
                + (handles.isEmpty() ? "" : handleMethod(source))
                + "}\n";
        write(pkg.isEmpty() ? simple : pkg + "." + simple, code, type);
        factories.add(pkg.isEmpty() ? simple : pkg + "." + simple);
    }

    private static String handleMethod(String source) {
        return "\n    // The generated class lives in the application's module, which always opens its packages to itself:\n"
                + "    // its private fields are reachable without any opens directive.\n"
                + "    private static java.lang.invoke.VarHandle handle(String name, Class<?> type) {\n"
                + "        try {\n"
                + "            return java.lang.invoke.MethodHandles.privateLookupIn(" + source + ".class,\n"
                + "                    java.lang.invoke.MethodHandles.lookup()).findVarHandle(" + source + ".class, name, type);\n"
                + "        } catch (ReflectiveOperationException e) {\n"
                + "            throw new ExceptionInInitializerError(e);\n"
                + "        }\n    }\n";
    }

    /** Why the generated code cannot fill {@code type}, or {@code null}. */
    private String unsupported(TypeElement type) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return "abstract class";
        }
        if (type.getModifiers().contains(Modifier.PRIVATE)) {
            return "private class";
        }
        if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
            return "inner class";
        }
        if (!type.getTypeParameters().isEmpty()) {
            return "generic class";
        }
        boolean constructor = ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .anyMatch(c -> c.getParameters().isEmpty() && !c.getModifiers().contains(Modifier.PRIVATE));
        if (!constructor) {
            return "no non-private no-argument constructor";
        }
        for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (field.getModifiers().contains(Modifier.FINAL)) {
                return "final field " + field.getSimpleName();
            }
            if (containsTypeVariable(field.asType())) {
                return "field " + field.getSimpleName() + " of a type variable";
            }
        }
        return null;
    }

    private String fieldCode(VariableElement field, String fieldName, boolean viaHandle) {
        String property = fieldName;
        String defaultValue = null;
        var annotation = annotation(field, CONFIG_PROPERTY);
        if (annotation != null) {
            String declared = stringMember(annotation, "name");
            if (declared != null && !declared.isEmpty() && !UNCONFIGURED_VALUE.equals(declared)) {
                property = declared;
            }
            String value = stringMember(annotation, "defaultValue");
            if (value != null && !UNCONFIGURED_VALUE.equals(value)) {
                defaultValue = value;
            }
        }
        TypeMirror type = field.asType();
        String erasure = erasure(type);
        String key = "name(prefix, \"" + escape(property) + "\")";
        String lookup;
        switch (erasure) {
            case "java.util.Optional" -> lookup = "config.getOptionalValue(" + key + ", " + optionalArgument(type) + ".class)";
            case "java.util.OptionalInt" -> lookup = "java.util.Optional.of(config.getOptionalValue(" + key
                    + ", Integer.class).map(java.util.OptionalInt::of).orElse(java.util.OptionalInt.empty()))";
            case "java.util.OptionalLong" -> lookup = "java.util.Optional.of(config.getOptionalValue(" + key
                    + ", Long.class).map(java.util.OptionalLong::of).orElse(java.util.OptionalLong.empty()))";
            case "java.util.OptionalDouble" -> lookup = "java.util.Optional.of(config.getOptionalValue(" + key
                    + ", Double.class).map(java.util.OptionalDouble::of).orElse(java.util.OptionalDouble.empty()))";
            default -> {
                lookup = "config.getOptionalValue(" + key + ", " + erasure + ".class)";
                if (defaultValue != null) {
                    lookup = lookup + ".or(() -> config.getConverter(" + erasure + ".class).map(c -> c.convert(\""
                            + escape(defaultValue) + "\")))";
                }
            }
        }
        String assign = viaHandle
                ? fieldName.toUpperCase() + "_HANDLE.set(instance, (" + castType(type) + ") value)"
                : "instance." + fieldName + " = (" + castType(type) + ") value";
        return "        {\n"
                + "            java.util.Optional<?> found = " + lookup + ";\n"
                + (erasure.equals("java.util.Optional")
                    ? "            Object value = found;\n            " + assign + ";\n"
                    : "            if (found.isPresent()) {\n                Object value = found.get();\n                "
                        + assign + ";\n            }\n")
                + "        }\n";
    }

    private String optionalArgument(TypeMirror type) {
        if (type instanceof DeclaredType declared && declared.getTypeArguments().size() == 1) {
            TypeMirror argument = declared.getTypeArguments().getFirst();
            if (argument.getKind() == TypeKind.DECLARED && ((DeclaredType) argument).getTypeArguments().isEmpty()) {
                return erasure(argument);
            }
        }
        return "String";
    }

    private String classPrefix(TypeElement type) {
        var annotation = annotation(type, CONFIG_PROPERTIES);
        String prefix = annotation == null ? null : stringMember(annotation, "prefix");
        return prefix == null ? UNCONFIGURED_PREFIX : prefix;
    }

    // ---- implicit converters ----

    private void collectConverterType(TypeMirror type, Element origin) {
        TypeMirror target = unwrap(type);
        if (target == null || target.getKind() != TypeKind.DECLARED) {
            return;
        }
        TypeElement element = (TypeElement) ((DeclaredType) target).asElement();
        String name = element.getQualifiedName().toString();
        if (BUILT_IN.contains(name) || converterTypes.containsKey(name) || !element.getTypeParameters().isEmpty()) {
            return;
        }
        if (factoryCall(element) == null || !accessible(element)) {
            return;
        }
        converterTypes.put(name, element);
        if (converterPackage == null) {
            Element top = origin;
            while (top.getEnclosingElement() != null && top.getKind() != ElementKind.PACKAGE) {
                top = top.getEnclosingElement();
            }
            converterPackage = top.toString();
        }
    }

    private TypeMirror unwrap(TypeMirror type) {
        TypeMirror current = type;
        while (true) {
            if (current.getKind() == TypeKind.ARRAY) {
                current = ((javax.lang.model.type.ArrayType) current).getComponentType();
                continue;
            }
            if (current.getKind() == TypeKind.DECLARED) {
                var declared = (DeclaredType) current;
                String name = ((TypeElement) declared.asElement()).getQualifiedName().toString();
                if (WRAPPERS.contains(name) && declared.getTypeArguments().size() == 1) {
                    current = declared.getTypeArguments().getFirst();
                    continue;
                }
            }
            return current.getKind() == TypeKind.DECLARED ? current : null;
        }
    }

    /** The §5.2 conversion of {@code type} as a {@code Function<String, T>} expression, or {@code null}. */
    private String factoryCall(TypeElement type) {
        String name = type.getQualifiedName().toString();
        if (type.getKind() == ElementKind.ENUM) {
            return "ofEnum";
        }
        for (String[] candidate : new String[][] {{"of", "java.lang.String"}, {"valueOf", "java.lang.String"},
                {"parse", "java.lang.CharSequence"}}) {
            var method = staticFactory(type, candidate[0], candidate[1]);
            if (method != null) {
                return call(name + "." + candidate[0] + "(value)", method, name);
            }
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return null;
        }
        for (ExecutableElement constructor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (constructor.getModifiers().contains(Modifier.PUBLIC) && constructor.getParameters().size() == 1
                    && erasure(constructor.getParameters().getFirst().asType()).equals("java.lang.String")) {
                return call("new " + name + "(value)", constructor, name);
            }
        }
        return null;
    }

    /**
     * The lambda calling {@code expression}; a checked exception it declares becomes an
     * {@link IllegalArgumentException}, as the reflective converter wraps it (an unchecked one passes through).
     */
    private String call(String expression, ExecutableElement executable, String target) {
        var checked = executable.getThrownTypes().stream().filter(t -> !unchecked(t)).map(this::erasure).toList();
        if (checked.isEmpty()) {
            return "value -> " + expression;
        }
        return "value -> {\n                try {\n                    return " + expression + ";\n                } catch ("
                + String.join(" | ", checked) + " e) {\n                    throw new IllegalArgumentException(\"Cannot convert '\" + value + \"' to "
                + target + "\", e);\n                }\n            }";
    }

    private ExecutableElement staticFactory(TypeElement type, String name, String parameter) {
        var types = processingEnv.getTypeUtils();
        for (ExecutableElement method : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))) {
            if (method.getSimpleName().contentEquals(name)
                    && method.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC))
                    && method.getParameters().size() == 1
                    && erasure(method.getParameters().getFirst().asType()).equals(parameter)
                    && types.isAssignable(types.erasure(method.getReturnType()), types.erasure(type.asType()))) {
                return method;
            }
        }
        return null;
    }

    private boolean unchecked(TypeMirror thrown) {
        var elements = processingEnv.getElementUtils();
        var types = processingEnv.getTypeUtils();
        return types.isAssignable(thrown, elements.getTypeElement("java.lang.RuntimeException").asType())
                || types.isAssignable(thrown, elements.getTypeElement("java.lang.Error").asType());
    }

    /** Whether code of the application can name {@code type}: public, and public all the way out. */
    private static boolean accessible(TypeElement type) {
        Element current = type;
        while (current instanceof TypeElement) {
            if (!current.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
            current = current.getEnclosingElement();
        }
        return true;
    }

    private void writeConverters() {
        if (converterTypes.isEmpty() || converterPackage == null) {
            return;
        }
        var cases = new StringBuilder();
        converterTypes.forEach((name, type) -> {
            String call = factoryCall(type);
            String binary = processingEnv.getElementUtils().getBinaryName(type).toString();
            cases.append("            case \"").append(binary).append("\" -> ")
                    .append("ofEnum".equals(call)
                            ? "io.vidocq.ravel.spi.ImplicitConversion.ofEnum(" + name + ".class)"
                            : "io.vidocq.ravel.spi.ImplicitConversion.of(" + name + ".class, " + call + ")")
                    .append(";\n");
        });
        String qualified = converterPackage.isEmpty() ? CONVERTERS_CLASS : converterPackage + "." + CONVERTERS_CLASS;
        String code = (converterPackage.isEmpty() ? "" : "package " + converterPackage + ";\n\n")
                + "/** Generated by Ravel's processor: the implicit converters of the application's types, without reflection. */\n"
                + "@javax.annotation.processing.Generated(\"io.vidocq.ravel.processor.RavelProcessor\")\n"
                + "public final class " + CONVERTERS_CLASS + " implements io.vidocq.ravel.spi.GeneratedConverters {\n\n"
                + "    @Override\n    @SuppressWarnings(\"unchecked\")\n"
                + "    public <T> org.eclipse.microprofile.config.spi.Converter<T> converter(Class<T> type) {\n"
                + "        Object converter = switch (type.getName()) {\n"
                + cases
                + "            default -> null;\n        };\n"
                + "        return (org.eclipse.microprofile.config.spi.Converter<T>) converter;\n    }\n}\n";
        write(qualified, code, null);
        converterServices = qualified;
    }

    private String converterServices;

    private void writeServices() {
        writeService(FACTORIES_SERVICE, factories);
        if (converterServices != null) {
            writeService(CONVERTERS_SERVICE, Set.of(converterServices));
        }
    }

    private void writeService(String path, Set<String> providers) {
        if (providers.isEmpty()) {
            return;
        }
        try {
            var resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", path);
            try (Writer writer = resource.openWriter()) {
                for (String provider : providers) {
                    writer.write(provider);
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Ravel: cannot write " + path + " (" + e.getMessage() + "); the reflective path is used");
        }
    }

    // ---- helpers ----

    private void write(String qualifiedName, String code, Element origin) {
        try {
            var file = origin == null
                    ? processingEnv.getFiler().createSourceFile(qualifiedName)
                    : processingEnv.getFiler().createSourceFile(qualifiedName, origin);
            try (Writer writer = file.openWriter()) {
                writer.write(code);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Ravel: cannot write " + qualifiedName + " (" + e.getMessage() + "); the reflective path is used");
        }
    }

    private void note(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    private String packageOf(TypeElement type) {
        return processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    }

    private String erasure(TypeMirror type) {
        TypeMirror erased = processingEnv.getTypeUtils().erasure(type);
        return erased.toString().replaceAll("<.*>", "");
    }

    private String boxed(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return processingEnv.getTypeUtils().boxedClass((javax.lang.model.type.PrimitiveType) type)
                    .getQualifiedName().toString();
        }
        return erasure(type);
    }

    /** The type a value is cast to before being set: the boxed type of a primitive, else the field's type. */
    private String castType(TypeMirror type) {
        return type.getKind().isPrimitive() ? boxed(type) : type.toString();
    }

    private boolean containsTypeVariable(TypeMirror type) {
        if (type.getKind() == TypeKind.TYPEVAR) {
            return true;
        }
        if (type instanceof DeclaredType declared) {
            return declared.getTypeArguments().stream().anyMatch(this::containsTypeVariable);
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return containsTypeVariable(((javax.lang.model.type.ArrayType) type).getComponentType());
        }
        return type.getKind() == TypeKind.WILDCARD;
    }

    private static javax.lang.model.element.AnnotationMirror annotation(Element element, String name) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(name)) {
                return mirror;
            }
        }
        return null;
    }

    private static String stringMember(javax.lang.model.element.AnnotationMirror annotation, String member) {
        for (var entry : annotation.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(member)) {
                return String.valueOf(entry.getValue().getValue());
            }
        }
        return null;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
