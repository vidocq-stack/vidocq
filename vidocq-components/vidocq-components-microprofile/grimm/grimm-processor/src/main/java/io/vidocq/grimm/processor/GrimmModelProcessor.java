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
package io.vidocq.grimm.processor;

import io.vidocq.grimm.internal.OpenApiSerializers;
import io.vidocq.grimm.internal.schema.SchemaGenerator;
import io.vidocq.grimm.internal.schema.SchemaRegistry;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generates {@code <Resource>$$GrimmModel} companions embedding the per-class OpenAPI
 * fragment as a JSON literal (CG-06 M1). The fragment is built with grimm-core's
 * <em>real</em> model objects and {@link SchemaGenerator} inside the javac JVM (scalar
 * types are JDK classes, loadable at compile time), then serialized with grimm's own
 * serializer — schemas are identical to the runtime scan by construction.
 *
 * <p><strong>M1 safety valve</strong>: any construct outside the structural JAX-RS
 * subset (class/method {@code @Path}, HTTP verbs, scalar
 * {@code @PathParam}/{@code @QueryParam}/{@code @HeaderParam}, {@code @Produces},
 * inferred 200 responses) skips the whole class with a compiler NOTE — partial
 * fragments would silently drop endpoints, so it is all or nothing per class. The
 * runtime {@code AnnotationScanner} remains the behavioural oracle and fallback.</p>
 */
public final class GrimmModelProcessor extends AbstractProcessor {

    private static final String SUFFIX = "$$GrimmModel";
    private static final String MP_OPENAPI_ANNOTATIONS_PREFIX = "org.eclipse.microprofile.openapi.annotations";

    private final Set<String> companionFqns = new TreeSet<>();
    private boolean servicesWritten;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("jakarta.ws.rs.Path");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement pathAnnotation = processingEnv.getElementUtils().getTypeElement("jakarta.ws.rs.Path");
        if (pathAnnotation != null) {
            for (Element e : roundEnv.getElementsAnnotatedWith(pathAnnotation)) {
                if (e.getKind() != ElementKind.CLASS) continue;
                TypeElement type = (TypeElement) e;
                try {
                    generate(type);
                } catch (SkipGeneration skip) {
                    note(type, "skipping " + type.getQualifiedName() + SUFFIX + " — "
                            + skip.getMessage() + " (runtime annotation scan will handle this class)");
                } catch (IOException | RuntimeException ex) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                            "Grimm fragment generation failed, runtime scan will handle it: " + ex, type);
                }
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

    // ------------------------------------------------------------------
    // Fragment building — real grimm-core model objects, M1 subset
    // ------------------------------------------------------------------

    private void generate(TypeElement type) throws SkipGeneration, IOException {
        if (type.getNestingKind().isNested()) {
            throw new SkipGeneration("nested resource classes are not emitted (M1)");
        }
        if (!type.getTypeParameters().isEmpty()) {
            throw new SkipGeneration("generic resource classes are not emitted (M1)");
        }
        rejectMpOpenApiAnnotations(type);

        String basePath = normalize(pathValue(type));
        String[] classProduces = producesOf(type);
        if (type.getAnnotation(Consumes.class) != null) {
            throw new SkipGeneration("@Consumes (request bodies) is outside the M1 subset");
        }

        SchemaGenerator schemaGenerator = new SchemaGenerator(new SchemaRegistry());
        OpenAPI fragment = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        fragment.setPaths(paths);
        boolean hasOperation = false;

        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (method.getModifiers().contains(Modifier.STATIC)
                    || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            rejectMpOpenApiAnnotations(method);
            String verb = httpVerbOf(method);
            boolean hasPath = method.getAnnotation(Path.class) != null;
            if (verb == null) {
                if (hasPath) {
                    throw new SkipGeneration("sub-resource locator " + method.getSimpleName()
                            + " is outside the M1 subset");
                }
                continue;
            }
            String template = joinPath(basePath, normalize(pathValue(method)));
            Operation operation = buildOperation(type, method, schemaGenerator,
                    producesOf(method) != null ? producesOf(method) : classProduces);

            PathItem item = paths.getPathItem(template);
            if (item == null) {
                item = OASFactory.createObject(PathItem.class);
                paths.addPathItem(template, item);
            }
            setOperation(item, verb, operation);
            hasOperation = true;
        }

        if (!hasOperation) {
            throw new SkipGeneration("no HTTP verb method in the M1 subset");
        }
        emit(type, OpenApiSerializers.toJson(fragment));
    }

    private Operation buildOperation(TypeElement type, ExecutableElement method,
                                     SchemaGenerator schemaGenerator, String[] produces)
            throws SkipGeneration {
        Operation operation = OASFactory.createObject(Operation.class);

        for (VariableElement param : method.getParameters()) {
            rejectMpOpenApiAnnotations(param);
            String name;
            Parameter.In in;
            var pathParam = param.getAnnotation(jakarta.ws.rs.PathParam.class);
            var queryParam = param.getAnnotation(jakarta.ws.rs.QueryParam.class);
            var headerParam = param.getAnnotation(jakarta.ws.rs.HeaderParam.class);
            if (pathParam != null) {
                name = pathParam.value();
                in = Parameter.In.PATH;
            } else if (queryParam != null) {
                name = queryParam.value();
                in = Parameter.In.QUERY;
            } else if (headerParam != null) {
                name = headerParam.value();
                in = Parameter.In.HEADER;
            } else {
                throw new SkipGeneration("parameter " + param.getSimpleName() + " of "
                        + method.getSimpleName() + " is not a scalar path/query/header parameter (M1)");
            }
            Parameter parameter = OASFactory.createObject(Parameter.class);
            parameter.setName(name);
            parameter.setIn(in);
            if (in == Parameter.In.PATH) {
                parameter.setRequired(Boolean.TRUE);
            }
            parameter.setSchema(schemaGenerator.generate(scalarClass(param.asType(), method)));
            operation.addParameter(parameter);
        }

        APIResponses responses = OASFactory.createObject(APIResponses.class);
        APIResponse inferred = OASFactory.createObject(APIResponse.class);
        inferred.setDescription("OK");
        TypeMirror returnType = method.getReturnType();
        boolean isVoid = returnType.getKind() == TypeKind.VOID;
        if (!isVoid && produces != null && produces.length > 0) {
            Content content = OASFactory.createObject(Content.class);
            Class<?> returnClass = scalarClass(returnType, method);
            for (String mt : produces) {
                MediaType mediaType = OASFactory.createObject(MediaType.class);
                mediaType.setSchema(schemaGenerator.generate(returnClass));
                content.addMediaType(mt, mediaType);
            }
            inferred.setContent(content);
        } else if (!isVoid) {
            scalarClass(returnType, method); // still enforce the scalar valve
        }
        responses.addAPIResponse("200", inferred);
        operation.setResponses(responses);
        return operation;
    }

    /** Maps a scalar TypeMirror to the real JDK class so SchemaGenerator runs for real. */
    private Class<?> scalarClass(TypeMirror type, ExecutableElement method) throws SkipGeneration {
        switch (type.getKind()) {
            case BOOLEAN: return boolean.class;
            case BYTE: return byte.class;
            case SHORT: return short.class;
            case INT: return int.class;
            case LONG: return long.class;
            case FLOAT: return float.class;
            case DOUBLE: return double.class;
            case CHAR: return char.class;
            case DECLARED: {
                String fqn = ((TypeElement) ((DeclaredType) type).asElement())
                        .getQualifiedName().toString();
                return switch (fqn) {
                    case "java.lang.String" -> String.class;
                    case "java.lang.Boolean" -> Boolean.class;
                    case "java.lang.Byte" -> Byte.class;
                    case "java.lang.Short" -> Short.class;
                    case "java.lang.Integer" -> Integer.class;
                    case "java.lang.Long" -> Long.class;
                    case "java.lang.Float" -> Float.class;
                    case "java.lang.Double" -> Double.class;
                    default -> throw newSkip(fqn, method);
                };
            }
            default:
                throw newSkip(type.toString(), method);
        }
    }

    private SkipGeneration newSkip(String typeName, ExecutableElement method) {
        return new SkipGeneration("non-scalar type " + typeName + " on "
                + method.getSimpleName() + " (M1 — schemas for POJOs stay on the runtime scan)");
    }

    private void setOperation(PathItem item, String verb, Operation operation) throws SkipGeneration {
        switch (verb) {
            case "GET" -> item.setGET(operation);
            case "POST" -> item.setPOST(operation);
            case "PUT" -> item.setPUT(operation);
            case "DELETE" -> item.setDELETE(operation);
            case "HEAD" -> item.setHEAD(operation);
            case "OPTIONS" -> item.setOPTIONS(operation);
            case "PATCH" -> item.setPATCH(operation);
            default -> throw new SkipGeneration("unsupported HTTP verb " + verb);
        }
    }

    private String httpVerbOf(ExecutableElement method) throws SkipGeneration {
        String verb = null;
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            HttpMethod meta = mirror.getAnnotationType().asElement().getAnnotation(HttpMethod.class);
            if (meta != null) {
                if (verb != null) {
                    throw new SkipGeneration("multiple HTTP verbs on " + method.getSimpleName());
                }
                verb = meta.value();
            }
        }
        return verb;
    }

    /** M1: the presence of any MP OpenAPI annotation hands the class to the runtime scan. */
    private void rejectMpOpenApiAnnotations(Element element) throws SkipGeneration {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String fqn = ((TypeElement) mirror.getAnnotationType().asElement())
                    .getQualifiedName().toString();
            if (fqn.startsWith(MP_OPENAPI_ANNOTATIONS_PREFIX)) {
                throw new SkipGeneration("MicroProfile OpenAPI annotation " + fqn
                        + " is outside the M1 subset");
            }
        }
    }

    private static String pathValue(Element element) {
        Path p = element.getAnnotation(Path.class);
        return p == null ? "" : p.value();
    }

    private String[] producesOf(Element element) {
        Produces produces = element.getAnnotation(Produces.class);
        return produces == null ? null : produces.value();
    }

    private static String normalize(String segment) {
        if (segment == null || segment.isEmpty()) return "";
        String s = segment;
        if (!s.startsWith("/")) s = "/" + s;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String joinPath(String base, String sub) {
        if (sub.isEmpty()) return base.isEmpty() ? "/" : base;
        if (base.isEmpty() || base.equals("/")) return sub;
        return base + sub;
    }

    // ------------------------------------------------------------------
    // Emission
    // ------------------------------------------------------------------

    private void emit(TypeElement type, String json) throws IOException {
        String packageName = processingEnv.getElementUtils().getPackageOf(type)
                .getQualifiedName().toString();
        String className = type.getSimpleName() + SUFFIX;
        String generatedFqn = packageName.isEmpty() ? className : packageName + "." + className;
        String resourceSource = type.getQualifiedName().toString();

        StringBuilder out = new StringBuilder(json.length() + 1024);
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("// Generated by io.vidocq.grimm.processor.GrimmModelProcessor — do not edit.\n");
        out.append("public final class ").append(className)
                .append(" implements io.vidocq.grimm.spi.gen.OpenApiContribution {\n\n");
        out.append("    @java.lang.Override\n    public java.lang.Class<?> resourceClass() {\n")
                .append("        return ").append(resourceSource).append(".class;\n    }\n\n");
        out.append("    @java.lang.Override\n    public java.lang.String openApiJson() {\n")
                .append("        return ").append(lit(json)).append(";\n    }\n");
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
                    "META-INF/services/io.vidocq.grimm.spi.gen.OpenApiContribution");
            try (Writer w = resource.openWriter()) {
                for (String fqn : companionFqns) {
                    w.write(fqn);
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not write OpenApiContribution services file (naming-convention "
                            + "resolution still applies): " + e);
        }
    }

    private void note(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "[grimm-processor] " + message, element);
    }
}
