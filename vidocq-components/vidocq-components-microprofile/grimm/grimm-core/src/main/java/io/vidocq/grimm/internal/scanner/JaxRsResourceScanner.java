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
package io.vidocq.grimm.internal.scanner;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callbacks;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterStyle;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.extensions.Extensions;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.links.Link;
import org.eclipse.microprofile.openapi.annotations.links.LinkParameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Encoding;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameters;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBodySchema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.OAuthFlow;
import org.eclipse.microprofile.openapi.annotations.security.OAuthFlows;
import org.eclipse.microprofile.openapi.annotations.security.OAuthScope;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.security.SecuritySchemes;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
import org.eclipse.microprofile.openapi.annotations.tags.Tags;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.parameters.Parameter.In;
import io.vidocq.grimm.internal.schema.SchemaGenerator;
import io.vidocq.grimm.internal.schema.BeanValidationMapper;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Scans JAX-RS resource classes for operation-level OpenAPI annotations.
 *
 * Spec §3.6: {@code @Operation}
 * Spec §3.7: {@code @Parameter}
 * Spec §3.8: {@code @RequestBody}
 * Spec §3.9: {@code @APIResponse} / {@code @APIResponses}
 */
final class JaxRsResourceScanner {

    private final SchemaGenerator schemaGenerator;

    JaxRsResourceScanner() {
        this(null);
    }

    JaxRsResourceScanner(SchemaGenerator schemaGenerator) {
        this.schemaGenerator = schemaGenerator;
    }

    void scan(Class<?> clazz, OpenAPI openAPI) {
        if (clazz.isInterface()) {
            // Client interfaces are not server endpoints and must not contribute paths.
            return;
        }
        Path classPath = clazz.getAnnotation(Path.class);
        processSecuritySchemes(clazz, openAPI);
        if (classPath == null) {
            return;
        }
        String[] classConsumes = mediaTypesOf(clazz.getAnnotation(Consumes.class));
        String[] classProduces = mediaTypesOf(clazz.getAnnotation(Produces.class));
        String basePath = normalizeBasePath(classPath.value());

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            String httpMethod = findHttpMethod(method);
            if (httpMethod == null) {
                continue;
            }
            Operation opAnn = method.getAnnotation(Operation.class);
            if (opAnn != null && opAnn.hidden()) {
                continue;
            }

            String fullPath = joinPath(basePath, methodSubPath(method));
            String[] consumes = override(classConsumes, mediaTypesOf(method.getAnnotation(Consumes.class)));
            String[] produces = override(classProduces, mediaTypesOf(method.getAnnotation(Produces.class)));

            PathItem pathItem = getOrCreatePathItem(openAPI, fullPath);
            org.eclipse.microprofile.openapi.models.Operation modelOp = buildOperation(clazz, method, opAnn, consumes, produces);
            org.eclipse.microprofile.openapi.models.Operation existing = existingOperation(pathItem, httpMethod);
            if (existing != null) {
                modelOp = mergeOperations(existing, modelOp);
            }
            assignOperation(pathItem, httpMethod, modelOp);
        }
    }

    // ---------- HTTP method / path helpers ----------

    private static String findHttpMethod(Method method) {
        for (Annotation a : method.getAnnotations()) {
            HttpMethod meta = a.annotationType().getAnnotation(HttpMethod.class);
            if (meta != null) {
                return meta.value();
            }
        }
        return null;
    }

    private static String normalizeBasePath(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "/";
        }
        String p = raw.startsWith("/") ? raw : "/" + raw;
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String methodSubPath(Method method) {
        Path methodPath = method.getAnnotation(Path.class);
        if (methodPath == null) {
            return "";
        }
        String v = methodPath.value();
        if (v.isEmpty() || v.equals("/")) {
            return "";
        }
        return v.startsWith("/") ? v : "/" + v;
    }

    private static String joinPath(String base, String sub) {
        if (sub.isEmpty()) {
            return base;
        }
        if ("/".equals(base)) {
            return sub;
        }
        return base + sub;
    }

    private static void assignOperation(PathItem item, String httpMethod, org.eclipse.microprofile.openapi.models.Operation op) {
        switch (httpMethod) {
            case "GET" -> item.setGET(op);
            case "POST" -> item.setPOST(op);
            case "PUT" -> item.setPUT(op);
            case "DELETE" -> item.setDELETE(op);
            case "PATCH" -> item.setPATCH(op);
            case "HEAD" -> item.setHEAD(op);
            case "OPTIONS" -> item.setOPTIONS(op);
            case "TRACE" -> item.setTRACE(op);
            default -> { /* unsupported verb ignored */ }
        }
    }

    private static org.eclipse.microprofile.openapi.models.Operation existingOperation(PathItem item, String httpMethod) {
        return switch (httpMethod) {
            case "GET" -> item.getGET();
            case "POST" -> item.getPOST();
            case "PUT" -> item.getPUT();
            case "DELETE" -> item.getDELETE();
            case "PATCH" -> item.getPATCH();
            case "HEAD" -> item.getHEAD();
            case "OPTIONS" -> item.getOPTIONS();
            case "TRACE" -> item.getTRACE();
            default -> null;
        };
    }

    /**
     * Merges two JAX-RS Operations mapped to the same path+verb (e.g. two @POST methods
     * differentiated by @Consumes). The resulting operation accumulates request body
     * content media types, response codes, parameters and tags.
     */
    private static org.eclipse.microprofile.openapi.models.Operation mergeOperations(
            org.eclipse.microprofile.openapi.models.Operation a,
            org.eclipse.microprofile.openapi.models.Operation b) {
        mergeRequestBody(a, b);
        mergeResponses(a, b);
        mergeParameters(a, b);
        mergeTags(a, b);
        if (a.getSummary() == null) {
            a.setSummary(b.getSummary());
        }
        if (a.getDescription() == null) {
            a.setDescription(b.getDescription());
        }
        if (a.getOperationId() == null) {
            a.setOperationId(b.getOperationId());
        }
        return a;
    }

    private static void mergeRequestBody(org.eclipse.microprofile.openapi.models.Operation target,
                                         org.eclipse.microprofile.openapi.models.Operation source) {
        var targetBody = target.getRequestBody();
        var sourceBody = source.getRequestBody();
        if (targetBody == null) {
            target.setRequestBody(sourceBody);
            return;
        }
        if (sourceBody == null) {
            return;
        }
        if (targetBody.getContent() == null) {
            targetBody.setContent(sourceBody.getContent());
        } else if (sourceBody.getContent() != null && sourceBody.getContent().getMediaTypes() != null) {
            for (var entry : sourceBody.getContent().getMediaTypes().entrySet()) {
                targetBody.getContent().addMediaType(entry.getKey(), entry.getValue());
            }
        }
        if (targetBody.getDescription() == null && sourceBody.getDescription() != null) {
            targetBody.setDescription(sourceBody.getDescription());
        }
    }

    private static void mergeResponses(org.eclipse.microprofile.openapi.models.Operation target,
                                       org.eclipse.microprofile.openapi.models.Operation source) {
        var sourceResponses = source.getResponses();
        if (sourceResponses == null) {
            return;
        }
        if (target.getResponses() == null) {
            target.setResponses(sourceResponses);
            return;
        }
        for (var entry : sourceResponses.getAPIResponses().entrySet()) {
            if (!target.getResponses().getAPIResponses().containsKey(entry.getKey())) {
                target.getResponses().addAPIResponse(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void mergeParameters(org.eclipse.microprofile.openapi.models.Operation target,
                                        org.eclipse.microprofile.openapi.models.Operation source) {
        if (source.getParameters() == null) {
            return;
        }
        for (var parameter : source.getParameters()) {
            boolean duplicate = target.getParameters() != null && target.getParameters().stream()
                    .anyMatch(p -> Objects.equals(p.getName(), parameter.getName())
                            && Objects.equals(p.getIn(), parameter.getIn()));
            if (!duplicate) {
                target.addParameter(parameter);
            }
        }
    }

    private static void mergeTags(org.eclipse.microprofile.openapi.models.Operation target,
                                  org.eclipse.microprofile.openapi.models.Operation source) {
        if (source.getTags() == null) {
            return;
        }
        for (var tag : source.getTags()) {
            if (target.getTags() == null || !target.getTags().contains(tag)) {
                target.addTag(tag);
            }
        }
    }

    private static PathItem getOrCreatePathItem(OpenAPI openAPI, String path) {
        Paths paths = openAPI.getPaths();
        if (paths == null) {
            paths = OASFactory.createObject(Paths.class);
            openAPI.setPaths(paths);
        }
        PathItem item = paths.getPathItem(path);
        if (item == null) {
            item = OASFactory.createObject(PathItem.class);
            paths.addPathItem(path, item);
        }
        return item;
    }

    // ---------- Operation construction ----------

    private org.eclipse.microprofile.openapi.models.Operation buildOperation(
            Class<?> resourceClass, Method method, Operation opAnn, String[] consumes, String[] produces) {
        var op = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Operation.class);

        if (opAnn != null) {
            if (!opAnn.operationId().isEmpty()) {
                op.setOperationId(opAnn.operationId());
            }
            if (!opAnn.summary().isEmpty()) {
                op.setSummary(opAnn.summary());
            }
            if (!opAnn.description().isEmpty()) {
                op.setDescription(opAnn.description());
            }
            if (opAnn.deprecated()) {
                op.setDeprecated(true);
            }
        }

        if (opAnn != null) {
            AnnotationModelMappings.applyExtensions(op, opAnn.extensions());
        }
        AnnotationModelMappings.applyExtensions(op, collectStandaloneExtensions(method));

        // MP OpenAPI 4.2: @ExternalDocumentation on a resource method documents the operation.
        ExternalDocumentation extDocs = method.getAnnotation(ExternalDocumentation.class);
        if (extDocs != null && !extDocs.url().isEmpty()) {
            op.setExternalDocs(AnnotationModelMappings.toModelExternalDocs(extDocs));
        }

        // Tags coming from @Operation are not in MP OpenAPI 4.1 Operation annotation members.
        // Operation tags are composed from class and method @Tag declarations (spec §3.5 / §3.7).
        applyClassAndMethodTags(resourceClass, method, op);
        applyOperationServers(resourceClass, method, op);
        applySecurityRequirements(resourceClass, method, op);

        processParameters(method, op);
        processRequestBody(method, op, consumes);
        processResponses(resourceClass, method, op, produces);
        processCallbacks(method, op);

        return op;
    }

    private static void applyClassAndMethodTags(Class<?> resourceClass,
                                                Method method,
                                                org.eclipse.microprofile.openapi.models.Operation op) {
        var methodTags = method.getAnnotationsByType(org.eclipse.microprofile.openapi.annotations.tags.Tag.class);
        boolean hasMethodTagAnnotation = methodTags.length > 0;
        for (var t : methodTags) {
            String tag = !t.name().isEmpty() ? t.name() : t.ref();
            addOperationTag(op, tag);
        }
        Tags methodTagContainer = method.getAnnotation(Tags.class);
        if (methodTagContainer != null) {
            hasMethodTagAnnotation = true;
            for (String ref : methodTagContainer.refs()) {
                addOperationTag(op, ref);
            }
        }
        if (!hasMethodTagAnnotation) {
            for (var t : resourceClass.getAnnotationsByType(org.eclipse.microprofile.openapi.annotations.tags.Tag.class)) {
                String tag = !t.name().isEmpty() ? t.name() : t.ref();
                addOperationTag(op, tag);
            }
            Tags classTagContainer = resourceClass.getAnnotation(Tags.class);
            if (classTagContainer != null) {
                for (String ref : classTagContainer.refs()) {
                    addOperationTag(op, ref);
                }
            }
        }
        // @Operation in MP OpenAPI 4.1 has no `tags()` member.
    }

    private static void addOperationTag(org.eclipse.microprofile.openapi.models.Operation op, String tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        if (op.getTags() == null || !op.getTags().contains(tag)) {
            op.addTag(tag);
        }
    }

    private static void applyOperationServers(Class<?> resourceClass,
                                              Method method,
                                              org.eclipse.microprofile.openapi.models.Operation op) {
        Server[] methodServers = method.getAnnotationsByType(Server.class);
        if (methodServers.length > 0) {
            for (Server server : methodServers) {
                addOperationServer(op, server);
            }
            return;
        }
        for (Server server : resourceClass.getAnnotationsByType(Server.class)) {
            addOperationServer(op, server);
        }
    }

    private static void addOperationServer(org.eclipse.microprofile.openapi.models.Operation op, Server annotation) {
        if (annotation.url().isEmpty()) {
            return;
        }
        org.eclipse.microprofile.openapi.models.servers.Server server = OASFactory
                .createObject(org.eclipse.microprofile.openapi.models.servers.Server.class);
        server.setUrl(annotation.url());
        if (!annotation.description().isEmpty()) {
            server.setDescription(annotation.description());
        }
        for (ServerVariable variableAnnotation : annotation.variables()) {
            String variableName = variableAnnotation.name();
            if (variableName.isEmpty()) {
                continue;
            }
            var variable = OASFactory.createObject(org.eclipse.microprofile.openapi.models.servers.ServerVariable.class);
            if (!variableAnnotation.description().isEmpty()) {
                variable.setDescription(variableAnnotation.description());
            }
            if (!variableAnnotation.defaultValue().isEmpty()) {
                variable.setDefaultValue(variableAnnotation.defaultValue());
            }
            if (variableAnnotation.enumeration().length > 0) {
                variable.setEnumeration(List.of(variableAnnotation.enumeration()));
            }
            server.addVariable(variableName, variable);
        }
        op.addServer(server);
    }

    // ---------- Parameters ----------

    private void processParameters(Method method, org.eclipse.microprofile.openapi.models.Operation op) {
        var paramAnnotations = method.getParameterAnnotations();
        var paramGenericTypes = method.getGenericParameterTypes();

        // Method-level @Parameters / @Parameter — explicit declarations.
        var methodLevelParameters = method.getAnnotation(Parameters.class);
        if (methodLevelParameters != null) {
            for (Parameter p : methodLevelParameters.value()) {
                addParameter(op, buildExplicitParameter(p, null, null, null, null));
            }
        }
        for (Parameter p : method.getAnnotationsByType(Parameter.class)) {
            addParameter(op, buildExplicitParameter(p, null, null, null, null));
        }

        for (int i = 0; i < paramAnnotations.length; i++) {
            Annotation[] anns = paramAnnotations[i];
            Type genericType = paramGenericTypes[i];

            if (isContextOrFormParameter(anns)) {
                continue; // @Context never appears in OpenAPI; @FormParam contributes to RequestBody (deferred).
            }

            Parameter explicit = findAnnotation(anns, Parameter.class);
            if (explicit != null && explicit.hidden()) {
                continue;
            }

            PathParam pp = findAnnotation(anns, PathParam.class);
            QueryParam qp = findAnnotation(anns, QueryParam.class);
            HeaderParam hp = findAnnotation(anns, HeaderParam.class);
            CookieParam cp = findAnnotation(anns, CookieParam.class);

            String inferredName = null;
            In inferredIn = null;
            if (pp != null) { inferredName = pp.value(); inferredIn = In.PATH; }
            else if (qp != null) { inferredName = qp.value(); inferredIn = In.QUERY; }
            else if (hp != null) { inferredName = hp.value(); inferredIn = In.HEADER; }
            else if (cp != null) { inferredName = cp.value(); inferredIn = In.COOKIE; }

            if (inferredIn == null && explicit == null) {
                // Not a parameter — it's the request body entity (handled elsewhere).
                continue;
            }

            var modelParam = buildExplicitParameter(explicit, inferredName, inferredIn, genericType, anns);
            addParameter(op, modelParam);
        }
    }

    private static void addParameter(org.eclipse.microprofile.openapi.models.Operation op,
                                     org.eclipse.microprofile.openapi.models.parameters.Parameter param) {
        if (param == null || param.getName() == null) {
            return;
        }
        var existing = op.getParameters();
        if (existing != null) {
            for (var e : existing) {
                if (Objects.equals(e.getName(), param.getName()) && e.getIn() == param.getIn()) {
                    return; // dedupe
                }
            }
        }
        op.addParameter(param);
    }

    private org.eclipse.microprofile.openapi.models.parameters.Parameter buildExplicitParameter(
            Parameter explicit, String inferredName, In inferredIn, Type sourceType, Annotation[] sourceAnnotations) {
        var p = OASFactory.createObject(org.eclipse.microprofile.openapi.models.parameters.Parameter.class);
        boolean hasExplicitContent = explicit != null && explicit.content().length > 0;

        String name = (explicit != null && !explicit.name().isEmpty()) ? explicit.name() : inferredName;
        In in = (explicit != null && explicit.in() != ParameterIn.DEFAULT) ? toModelIn(explicit.in()) : inferredIn;

        if (name == null) {
            return null;
        }
        p.setName(name);
        if (in != null) {
            p.setIn(in);
        }
        if (in == In.PATH) {
            p.setRequired(Boolean.TRUE);
        }
        if (explicit != null) {
            if (!explicit.description().isEmpty()) {
                p.setDescription(explicit.description());
            }
            if (explicit.required()) {
                p.setRequired(Boolean.TRUE);
            }
            if (explicit.deprecated()) {
                p.setDeprecated(Boolean.TRUE);
            }
            if (explicit.allowEmptyValue()) {
                p.setAllowEmptyValue(Boolean.TRUE);
            }
            if (explicit.style() != ParameterStyle.DEFAULT) {
                p.setStyle(toModelParameterStyle(explicit.style()));
            }
            if (!explicit.example().isEmpty()) {
                p.setExample(explicit.example());
            }
            AnnotationModelMappings.applyExtensions(p, explicit.extensions());
            if (hasExplicitContent) {
                var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
                for (Content c : explicit.content()) {
                    String mt = c.mediaType().isEmpty() ? "*/*" : c.mediaType();
                    MediaType mediaType = OASFactory.createObject(MediaType.class);
                    applyContentAnnotation(c, mediaType, sourceType, true);
                    content.addMediaType(mt, mediaType);
                }
                p.setContent(content);
            }
        }
        if (sourceAnnotations != null && BeanValidationMapper.hasNotNull(sourceAnnotations)) {
            p.setRequired(Boolean.TRUE);
        }
        // Schema (§3.10): site-level @Schema on the parameter, or inferred from the type.
        if (!hasExplicitContent && schemaGenerator != null) {
            var sourceSchema = sourceAnnotations != null
                    ? findAnnotation(sourceAnnotations, org.eclipse.microprofile.openapi.annotations.media.Schema.class)
                    : null;
            if (sourceSchema != null && sourceSchema.hidden()) {
                return p;
            }
            var siteAnn = explicit != null ? explicit.schema() : null;
            if (siteAnn == null && sourceSchema != null) {
                siteAnn = sourceSchema;
            }
            // The annotation's default name is empty — treat a zero-content @Schema as absent.
            if (siteAnn != null && hasAnyContent(siteAnn)) {
                Type targetType = sourceType != null ? sourceType : Object.class;
                var schema = schemaGenerator.generate(targetType, siteAnn);
                BeanValidationMapper.apply(schema, sourceAnnotations);
                p.setSchema(schema);
            } else if (sourceType != null) {
                var schema = schemaGenerator.generate(sourceType);
                BeanValidationMapper.apply(schema, sourceAnnotations);
                p.setSchema(schema);
            }
        }
        return p;
    }

    private static boolean hasAnyContent(org.eclipse.microprofile.openapi.annotations.media.Schema s) {
        return s.implementation() != Void.class
                || !s.ref().isEmpty()
                || s.type() != org.eclipse.microprofile.openapi.annotations.enums.SchemaType.DEFAULT
                || !s.format().isEmpty()
                || !s.description().isEmpty()
                || !s.title().isEmpty()
                || !s.example().isEmpty()
                || s.maxProperties() != Integer.MAX_VALUE
                || s.minProperties() != 0
                || s.maxItems() != Integer.MAX_VALUE
                || s.minItems() != 0
                || s.maxLength() != Integer.MAX_VALUE
                || s.minLength() != 0
                || s.properties().length > 0
                || s.required()
                || s.enumeration().length > 0;
    }

    private static boolean isContextOrFormParameter(Annotation[] annotations) {
        return hasAnnotation(annotations, Context.class) || hasAnnotation(annotations, FormParam.class);
    }

    private static boolean isNonBodyJaxRsParameter(Annotation[] annotations) {
        return hasAnnotation(annotations, Context.class)
                || hasAnnotation(annotations, PathParam.class)
                || hasAnnotation(annotations, QueryParam.class)
                || hasAnnotation(annotations, HeaderParam.class)
                || hasAnnotation(annotations, CookieParam.class);
    }

    private static org.eclipse.microprofile.openapi.models.parameters.Parameter.Style toModelParameterStyle(ParameterStyle style) {
        return switch (style) {
            case MATRIX -> org.eclipse.microprofile.openapi.models.parameters.Parameter.Style.MATRIX;
            case LABEL -> org.eclipse.microprofile.openapi.models.parameters.Parameter.Style.LABEL;
            case FORM -> org.eclipse.microprofile.openapi.models.parameters.Parameter.Style.FORM;
            case SIMPLE -> org.eclipse.microprofile.openapi.models.parameters.Parameter.Style.SIMPLE;
            case SPACEDELIMITED -> org.eclipse.microprofile.openapi.models.parameters.Parameter.Style.SPACEDELIMITED;
            case PIPEDELIMITED -> org.eclipse.microprofile.openapi.models.parameters.Parameter.Style.PIPEDELIMITED;
            case DEEPOBJECT -> org.eclipse.microprofile.openapi.models.parameters.Parameter.Style.DEEPOBJECT;
            case DEFAULT -> null;
        };
    }

    private static In toModelIn(ParameterIn in) {
        return switch (in) {
            case QUERY -> In.QUERY;
            case HEADER -> In.HEADER;
            case PATH -> In.PATH;
            case COOKIE -> In.COOKIE;
            case DEFAULT -> null;
        };
    }

    // ---------- Request body ----------

    private void processRequestBody(Method method, org.eclipse.microprofile.openapi.models.Operation op, String[] consumes) {
        var paramAnnotations = method.getParameterAnnotations();
        var paramGenericTypes = method.getGenericParameterTypes();

        RequestBody explicit = null;
        int entityIndex = -1;
        Class<?> requestBodySchemaType = null;
        List<Integer> formParamIndices = new ArrayList<>();

        for (int i = 0; i < paramAnnotations.length; i++) {
            Annotation[] anns = paramAnnotations[i];
            if (hasAnnotation(anns, FormParam.class)) {
                formParamIndices.add(i);
                continue;
            }
            if (isNonBodyJaxRsParameter(anns)) {
                // Non-body JAX-RS params are handled in processParameters.
                continue;
            }
            RequestBody rb = findAnnotation(anns, RequestBody.class);
            RequestBodySchema requestBodySchema = findAnnotation(anns, RequestBodySchema.class);
            if (rb != null) {
                explicit = rb;
                entityIndex = i;
            } else if (entityIndex == -1) {
                // first un-annotated parameter = entity body
                entityIndex = i;
            }
            if (requestBodySchema != null) {
                requestBodySchemaType = requestBodySchema.value();
                if (entityIndex == -1) {
                    entityIndex = i;
                }
            }
        }

        // Also check method-level @RequestBody
        if (explicit == null) {
            RequestBody methodLevel = method.getAnnotation(RequestBody.class);
            if (methodLevel != null) {
                explicit = methodLevel;
            }
        }

        // Synthesize a form-urlencoded request body when only @FormParam params are present.
        if (entityIndex == -1 && explicit == null && !formParamIndices.isEmpty()) {
            op.setRequestBody(buildFormUrlEncodedBody(paramAnnotations, paramGenericTypes, formParamIndices, consumes));
            return;
        }

        if (entityIndex == -1 && explicit == null) {
            return;
        }

        var modelBody = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.parameters.RequestBody.class);
        if (explicit != null) {
            if (!explicit.description().isEmpty()) {
                modelBody.setDescription(explicit.description());
            }
            if (explicit.required()) {
                modelBody.setRequired(Boolean.TRUE);
            }
            if (!explicit.ref().isEmpty()) {
                modelBody.setRef(explicit.ref());
            }
            AnnotationModelMappings.applyExtensions(modelBody, explicit.extensions());
            if (explicit.content().length > 0) {
                var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
                Type bodyType = requestBodySchemaType != null
                        ? requestBodySchemaType
                        : (entityIndex >= 0 ? paramGenericTypes[entityIndex] : null);
                for (Content c : explicit.content()) {
                    String mt = c.mediaType().isEmpty() ? "*/*" : c.mediaType();
                    MediaType mediaType = OASFactory.createObject(MediaType.class);
                    applyContentAnnotation(c, mediaType, bodyType, true);
                    content.addMediaType(mt, mediaType);
                }
                modelBody.setContent(content);
            }
        }
        if (modelBody.getContent() == null) {
            // infer content from @Consumes
            var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
            String[] effective = consumes.length > 0 ? consumes : new String[]{"application/json"};
            Type entityType = (entityIndex >= 0) ? paramGenericTypes[entityIndex] : null;
            org.eclipse.microprofile.openapi.annotations.media.Schema entitySchemaAnnotation = entityIndex >= 0
                    ? findAnnotation(paramAnnotations[entityIndex], org.eclipse.microprofile.openapi.annotations.media.Schema.class)
                    : null;
            for (String mt : effective) {
                MediaType mediaType = OASFactory.createObject(MediaType.class);
                if (schemaGenerator != null && requestBodySchemaType != null) {
                    mediaType.setSchema(schemaGenerator.generate(requestBodySchemaType));
                } else if (schemaGenerator != null && entityType != null) {
                    if (entitySchemaAnnotation != null && entitySchemaAnnotation.hidden()) {
                        // @Schema(hidden=true) on entity parameter suppresses requestBody schema publication.
                    } else if (entitySchemaAnnotation != null && hasAnyContent(entitySchemaAnnotation)) {
                        mediaType.setSchema(schemaGenerator.generate(entityType, entitySchemaAnnotation));
                    } else {
                        mediaType.setSchema(schemaGenerator.generate(entityType));
                    }
                }
                content.addMediaType(mt, mediaType);
            }
            modelBody.setContent(content);
        }
        if (modelBody.getRequired() == null && explicit == null && entityIndex >= 0) {
            modelBody.setRequired(Boolean.TRUE);
        }
        if (modelBody.getRequired() == null && explicit != null) {
            modelBody.setRequired(Boolean.FALSE);
        }
        op.setRequestBody(modelBody);
    }

    private org.eclipse.microprofile.openapi.models.parameters.RequestBody buildFormUrlEncodedBody(
            Annotation[][] paramAnnotations,
            Type[] paramGenericTypes,
            List<Integer> formParamIndices,
            String[] consumes) {
        var modelBody = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.parameters.RequestBody.class);
        var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
        String[] effective = consumes.length > 0 ? consumes : new String[]{"application/x-www-form-urlencoded"};
        for (String mt : effective) {
            MediaType mediaType = OASFactory.createObject(MediaType.class);
            if (schemaGenerator != null) {
                var schema = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Schema.class);
                schema.addType(org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.OBJECT);
                for (int idx : formParamIndices) {
                    FormParam fp = findAnnotation(paramAnnotations[idx], FormParam.class);
                    if (fp == null || fp.value().isEmpty()) {
                        continue;
                    }
                    schema.addProperty(fp.value(), schemaGenerator.generate(paramGenericTypes[idx]));
                }
                mediaType.setSchema(schema);
            }
            content.addMediaType(mt, mediaType);
        }
        modelBody.setContent(content);
        return modelBody;
    }

    // ---------- Responses ----------

    private void processResponses(Class<?> resourceClass,
                                  Method method,
                                  org.eclipse.microprofile.openapi.models.Operation op,
                                  String[] produces) {
        var responses = OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponses.class);
        APIResponses aggregate = method.getAnnotation(APIResponses.class);

        APIResponse[] declared = collectResponses(resourceClass, method);
        if (declared.length == 0) {
            // Spec §3.9: infer a default 200 response.
            var inferred = OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponse.class);
            inferred.setDescription("OK");
            if (produces.length > 0 && method.getReturnType() != void.class && method.getReturnType() != Void.class) {
                var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
                Type returnType = method.getGenericReturnType();
                for (String mt : produces) {
                    MediaType mediaType = OASFactory.createObject(MediaType.class);
                    if (schemaGenerator != null) {
                        mediaType.setSchema(schemaGenerator.generate(returnType));
                    }
                    content.addMediaType(mt, mediaType);
                }
                inferred.setContent(content);
            }
            responses.addAPIResponse("200", inferred);
        } else {
            for (APIResponse r : declared) {
                String code = r.responseCode().isEmpty() ? "default" : r.responseCode();
                var modelResp = OASFactory.createObject(
                        org.eclipse.microprofile.openapi.models.responses.APIResponse.class);
                if (!r.description().isEmpty()) {
                    modelResp.setDescription(r.description());
                }
                if (!r.ref().isEmpty()) {
                    modelResp.setRef(r.ref());
                }
                if (r.content().length > 0) {
                    var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
                    for (Content c : r.content()) {
                        String[] mediaTypes = c.mediaType().isEmpty()
                                ? (produces.length > 0 ? produces : new String[] {"*/*"})
                                : new String[] {c.mediaType()};
                        for (String mt : mediaTypes) {
                            MediaType mediaType = OASFactory.createObject(MediaType.class);
                            applyContentAnnotation(c, mediaType, method.getGenericReturnType(), false);
                            content.addMediaType(mt, mediaType);
                        }
                    }
                    modelResp.setContent(content);
                }
                applyResponseHeaders(r, modelResp);
                applyResponseLinks(r, modelResp);
                AnnotationModelMappings.applyExtensions(modelResp, r.extensions());
                responses.addAPIResponse(code, modelResp);
            }
        }

        if (aggregate != null) {
            AnnotationModelMappings.applyExtensions(responses, aggregate.extensions());
        }
        applyExceptionMapperResponses(method, responses, produces);
        processResponseSchemas(method, responses, produces);
        op.setResponses(responses);
    }

    private void applyExceptionMapperResponses(Method method,
                                               org.eclipse.microprofile.openapi.models.responses.APIResponses responses,
                                               String[] produces) {
        ClassLoader classLoader = method.getDeclaringClass().getClassLoader();
        for (Class<?> exceptionType : method.getExceptionTypes()) {
            String mapperClassName = exceptionType.getPackageName() + "." + exceptionType.getSimpleName() + "Mapper";
            Class<?> mapperClass;
            try {
                mapperClass = Class.forName(mapperClassName, false, classLoader);
            } catch (ClassNotFoundException ignored) {
                continue;
            }
            if (!ExceptionMapper.class.isAssignableFrom(mapperClass)) {
                continue;
            }
            for (APIResponse mapperResponse : mapperClass.getAnnotationsByType(APIResponse.class)) {
                addExceptionMapperResponse(method, responses, produces, mapperResponse);
            }
            for (Method mapperMethod : mapperClass.getDeclaredMethods()) {
                if (mapperMethod.isSynthetic()
                        || !"toResponse".equals(mapperMethod.getName())
                        || mapperMethod.getParameterCount() != 1
                        || !mapperMethod.getParameterTypes()[0].isAssignableFrom(exceptionType)) {
                    continue;
                }
                for (APIResponse mapperResponse : mapperMethod.getAnnotationsByType(APIResponse.class)) {
                    addExceptionMapperResponse(method, responses, produces, mapperResponse);
                }
            }
        }
    }

    private void addExceptionMapperResponse(Method method,
                                            org.eclipse.microprofile.openapi.models.responses.APIResponses responses,
                                            String[] produces,
                                            APIResponse mapperResponse) {
        String code = mapperResponse.responseCode().isEmpty() ? "default" : mapperResponse.responseCode();
        if (responses.getAPIResponse(code) != null) {
            return;
        }
        var modelResp = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.responses.APIResponse.class);
        if (!mapperResponse.description().isEmpty()) {
            modelResp.setDescription(mapperResponse.description());
        }
        if (!mapperResponse.ref().isEmpty()) {
            modelResp.setRef(mapperResponse.ref());
        }
        if (mapperResponse.content().length > 0) {
            var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
            for (Content c : mapperResponse.content()) {
                String[] mediaTypes = c.mediaType().isEmpty()
                        ? (produces.length > 0 ? produces : new String[] {"*/*"})
                        : new String[] {c.mediaType()};
                for (String mt : mediaTypes) {
                    MediaType mediaType = OASFactory.createObject(MediaType.class);
                    applyContentAnnotation(c, mediaType, method.getGenericReturnType(), false);
                    content.addMediaType(mt, mediaType);
                }
            }
            modelResp.setContent(content);
        }
        applyResponseHeaders(mapperResponse, modelResp);
        applyResponseLinks(mapperResponse, modelResp);
        AnnotationModelMappings.applyExtensions(modelResp, mapperResponse.extensions());
        responses.addAPIResponse(code, modelResp);
    }

    @SuppressWarnings("removal")
    private void applyResponseHeaders(APIResponse responseAnnotation,
                                      org.eclipse.microprofile.openapi.models.responses.APIResponse modelResponse) {
        for (Header headerAnnotation : responseAnnotation.headers()) {
            String name = resolveHeaderName(headerAnnotation);
            if (name == null || name.isBlank()) {
                continue;
            }
            var header = OASFactory.createObject(org.eclipse.microprofile.openapi.models.headers.Header.class);
            if (!headerAnnotation.ref().isEmpty()) {
                header.setRef(headerAnnotation.ref());
            }
            if (!headerAnnotation.description().isEmpty()) {
                header.setDescription(headerAnnotation.description());
            }
            if (headerAnnotation.required()) {
                header.setRequired(true);
            }
            header.setDeprecated(headerAnnotation.deprecated());
            header.setAllowEmptyValue(headerAnnotation.allowEmptyValue());
            AnnotationModelMappings.applyHeaderExamples(header, headerAnnotation);
            if (schemaGenerator != null && hasAnyContent(headerAnnotation.schema())) {
                header.setSchema(schemaGenerator.generate(Object.class, headerAnnotation.schema()));
            }
            AnnotationModelMappings.applyExtensions(header, headerAnnotation.extensions());
            modelResponse.addHeader(name, header);
        }
    }

    private void applyResponseLinks(APIResponse responseAnnotation,
                                    org.eclipse.microprofile.openapi.models.responses.APIResponse modelResponse) {
        for (Link linkAnnotation : responseAnnotation.links()) {
            String name = linkAnnotation.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            var link = OASFactory.createObject(org.eclipse.microprofile.openapi.models.links.Link.class);
            if (!linkAnnotation.ref().isEmpty()) {
                link.setRef(linkAnnotation.ref());
            }
            if (!linkAnnotation.operationId().isEmpty()) {
                link.setOperationId(linkAnnotation.operationId());
            }
            if (!linkAnnotation.operationRef().isEmpty()) {
                link.setOperationRef(linkAnnotation.operationRef());
            }
            if (!linkAnnotation.description().isEmpty()) {
                link.setDescription(linkAnnotation.description());
            }
            if (!linkAnnotation.requestBody().isEmpty()) {
                link.setRequestBody(linkAnnotation.requestBody());
            }
            if (linkAnnotation.parameters().length > 0) {
                for (LinkParameter parameter : linkAnnotation.parameters()) {
                    if (!parameter.name().isEmpty() && !parameter.expression().isEmpty()) {
                        link.addParameter(parameter.name(), parameter.expression());
                    }
                }
            }
            if (!linkAnnotation.server().url().isEmpty()) {
                var server = OASFactory.createObject(org.eclipse.microprofile.openapi.models.servers.Server.class);
                server.setUrl(linkAnnotation.server().url());
                if (!linkAnnotation.server().description().isEmpty()) {
                    server.setDescription(linkAnnotation.server().description());
                }
                AnnotationModelMappings.applyExtensions(server, linkAnnotation.server().extensions());
                link.setServer(server);
            }
            AnnotationModelMappings.applyExtensions(link, linkAnnotation.extensions());
            modelResponse.addLink(name, link);
        }
    }

    private void processResponseSchemas(
            Method method,
            org.eclipse.microprofile.openapi.models.responses.APIResponses responses,
            String[] produces) {
        if (schemaGenerator == null) {
            return;
        }
        for (APIResponseSchema schemaAnnotation : method.getAnnotationsByType(APIResponseSchema.class)) {
            String responseCode = schemaAnnotation.responseCode().isEmpty()
                    ? defaultResponseCodeFor(method)
                    : schemaAnnotation.responseCode();
            var response = responses.getAPIResponse(responseCode);
            if (response == null) {
                response = OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponse.class);
                responses.addAPIResponse(responseCode, response);
            }
            if (!schemaAnnotation.responseDescription().isEmpty()) {
                response.setDescription(schemaAnnotation.responseDescription());
            } else if (response.getDescription() == null || response.getDescription().isBlank()) {
                response.setDescription(defaultDescriptionForStatus(responseCode));
            }

            var content = response.getContent();
            if (content == null) {
                content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
                response.setContent(content);
            }
            String[] mediaTypes = produces.length > 0 ? produces : new String[] {"*/*"};
            for (String mediaTypeName : mediaTypes) {
                MediaType mediaType = content.getMediaType(mediaTypeName);
                if (mediaType == null) {
                    mediaType = OASFactory.createObject(MediaType.class);
                    content.addMediaType(mediaTypeName, mediaType);
                }
                mediaType.setSchema(schemaGenerator.generate(schemaAnnotation.value()));
            }
        }
    }

    private String defaultResponseCodeFor(Method method) {
        return (method.getReturnType() == void.class || method.getReturnType() == Void.class) ? "204" : "200";
    }

    private static APIResponse[] collectResponses(Class<?> resourceClass, Method method) {
        APIResponses aggregate = method.getAnnotation(APIResponses.class);
        List<APIResponse> all = new ArrayList<>();
        APIResponses classAggregate = resourceClass.getAnnotation(APIResponses.class);
        if (classAggregate != null) {
            all.addAll(Arrays.asList(classAggregate.value()));
        }
        all.addAll(Arrays.asList(resourceClass.getAnnotationsByType(APIResponse.class)));
        if (aggregate != null) {
            all.addAll(Arrays.asList(aggregate.value()));
        }
        all.addAll(Arrays.asList(method.getAnnotationsByType(APIResponse.class)));
        return all.toArray(APIResponse[]::new);
    }

    // ---------- Callbacks ----------

    private void processCallbacks(Method method, org.eclipse.microprofile.openapi.models.Operation op) {
        List<Callback> all = new ArrayList<>();
        Callbacks aggregate = method.getAnnotation(Callbacks.class);
        if (aggregate != null) {
            all.addAll(Arrays.asList(aggregate.value()));
        }
        Callback single = method.getAnnotation(Callback.class);
        if (single != null) {
            all.add(single);
        }
        if (all.isEmpty()) {
            return;
        }
        for (Callback cb : all) {
            if (cb.name().isEmpty()) {
                continue;
            }
            var modelCb = OASFactory.createObject(org.eclipse.microprofile.openapi.models.callbacks.Callback.class);
            if (!cb.ref().isEmpty()) {
                modelCb.setRef(cb.ref());
            }
            AnnotationModelMappings.applyExtensions(modelCb, cb.extensions());
            String urlExpr = cb.callbackUrlExpression();
            if (!urlExpr.isEmpty() && cb.operations().length > 0) {
                var pathItem = OASFactory.createObject(PathItem.class);
                for (CallbackOperation co : cb.operations()) {
                    var inner = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Operation.class);
                    if (!co.summary().isEmpty()) {
                        inner.setSummary(co.summary());
                    }
                    if (!co.description().isEmpty()) {
                        inner.setDescription(co.description());
                    }
                    AnnotationModelMappings.applyExtensions(inner, co.extensions());
                    for (SecurityRequirement requirement : co.security()) {
                        addSecurityRequirement(inner, requirement);
                    }
                    for (var requirementSet : co.securitySets()) {
                        if (requirementSet.value().length == 0) {
                            inner.addSecurityRequirement(
                                    OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class)
                            );
                            continue;
                        }
                        var groupedRequirement = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class);
                        boolean hasScheme = false;
                        for (SecurityRequirement requirement : requirementSet.value()) {
                            if (!requirement.name().isEmpty()) {
                                groupedRequirement.addScheme(requirement.name(), List.of(requirement.scopes()));
                                hasScheme = true;
                            }
                        }
                        if (hasScheme) {
                            inner.addSecurityRequirement(groupedRequirement);
                        }
                    }
                    for (APIResponse responseAnnotation : co.responses()) {
                        String code = responseAnnotation.responseCode().isEmpty() ? "default" : responseAnnotation.responseCode();
                        if (inner.getResponses() == null) {
                            inner.setResponses(OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponses.class));
                        }
                        var callbackResponse = OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponse.class);
                        if (!responseAnnotation.description().isEmpty()) {
                            callbackResponse.setDescription(responseAnnotation.description());
                        }
                        if (!responseAnnotation.ref().isEmpty()) {
                            callbackResponse.setRef(responseAnnotation.ref());
                        }
                        if (responseAnnotation.content().length > 0) {
                            var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
                            for (Content contentAnnotation : responseAnnotation.content()) {
                                String mediaTypeName = contentAnnotation.mediaType().isEmpty()
                                        ? "*/*"
                                        : contentAnnotation.mediaType();
                                MediaType mediaType = OASFactory.createObject(MediaType.class);
                                applyContentAnnotation(contentAnnotation, mediaType, method.getGenericReturnType(), false);
                                content.addMediaType(mediaTypeName, mediaType);
                            }
                            callbackResponse.setContent(content);
                        }
                        inner.getResponses().addAPIResponse(code, callbackResponse);
                    }
                    assignOperation(pathItem, co.method().toUpperCase(), inner);
                }
                modelCb.addPathItem(urlExpr, pathItem);
            }
            op.addCallback(cb.name(), modelCb);
        }
    }

    // ---------- Utility ----------

    private static boolean hasAnnotation(Annotation[] anns, Class<? extends Annotation> type) {
        return findAnnotation(anns, type) != null;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findAnnotation(Annotation[] anns, Class<A> type) {
        for (Annotation a : anns) {
            if (type.isInstance(a)) {
                return (A) a;
            }
        }
        return null;
    }

    private static String[] mediaTypesOf(Consumes a) {
        return a == null ? new String[0] : a.value();
    }

    private static String[] mediaTypesOf(Produces a) {
        return a == null ? new String[0] : a.value();
    }

    private static String[] override(String[] classLevel, String[] methodLevel) {
        return methodLevel.length > 0 ? methodLevel : classLevel;
    }

    private static Type entityType(Method method, int entityIndex) {
        if (entityIndex < 0) {
            return method.getGenericReturnType();
        }
        return method.getGenericParameterTypes()[entityIndex];
    }

    private void processSecuritySchemes(Class<?> clazz, OpenAPI openAPI) {
        List<SecurityScheme> annotations = new ArrayList<>();
        SecuritySchemes grouped = clazz.getAnnotation(SecuritySchemes.class);
        if (grouped != null) {
            annotations.addAll(Arrays.asList(grouped.value()));
        }
        annotations.addAll(Arrays.asList(clazz.getAnnotationsByType(SecurityScheme.class)));
        if (annotations.isEmpty()) {
            return;
        }

        var components = openAPI.getComponents();
        if (components == null) {
            components = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Components.class);
            openAPI.setComponents(components);
        }
        for (SecurityScheme schemeAnnotation : annotations) {
            String name = schemeAnnotation.securitySchemeName();
            if (name == null || name.isBlank()) {
                continue;
            }
            var scheme = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityScheme.class);
            if (!schemeAnnotation.description().isEmpty()) {
                scheme.setDescription(schemeAnnotation.description());
            }
            if (!schemeAnnotation.ref().isEmpty()) {
                scheme.setRef(schemeAnnotation.ref());
            }
            if (!schemeAnnotation.apiKeyName().isEmpty()) {
                scheme.setName(schemeAnnotation.apiKeyName());
            }
            if (!schemeAnnotation.scheme().isEmpty()) {
                scheme.setScheme(schemeAnnotation.scheme());
            }
            if (!schemeAnnotation.bearerFormat().isEmpty()) {
                scheme.setBearerFormat(schemeAnnotation.bearerFormat());
            }
            if (!schemeAnnotation.openIdConnectUrl().isEmpty()) {
                scheme.setOpenIdConnectUrl(schemeAnnotation.openIdConnectUrl());
            }
            scheme.setType(toModelSecuritySchemeType(schemeAnnotation.type()));
            org.eclipse.microprofile.openapi.models.security.SecurityScheme.In in = toModelSecuritySchemeIn(schemeAnnotation.in());
            if (in != null) {
                scheme.setIn(in);
            }
            org.eclipse.microprofile.openapi.models.security.OAuthFlows flows = toModelFlows(schemeAnnotation.flows());
            if (flows != null) {
                scheme.setFlows(flows);
            }
            AnnotationModelMappings.applyExtensions(scheme, schemeAnnotation.extensions());
            components.addSecurityScheme(name, scheme);
        }
    }

    private void applySecurityRequirements(
            Class<?> resourceClass,
            Method method,
            org.eclipse.microprofile.openapi.models.Operation op) {
        SecurityRequirement[] methodRequirements = method.getAnnotationsByType(SecurityRequirement.class);
        SecurityRequirementsSet[] methodRequirementSets = collectSecurityRequirementSets(method);
        boolean methodOverridesClass = methodRequirementSets.length > 0;

        if (!methodOverridesClass) {
            for (SecurityRequirement requirement : resourceClass.getAnnotationsByType(SecurityRequirement.class)) {
                addSecurityRequirement(op, requirement);
            }
            for (SecurityRequirementsSet requirementSet : collectSecurityRequirementSets(resourceClass)) {
                addSecurityRequirementSet(op, requirementSet);
            }
        }

        for (SecurityRequirement requirement : methodRequirements) {
            addSecurityRequirement(op, requirement);
        }
        for (SecurityRequirementsSet requirementSet : methodRequirementSets) {
            addSecurityRequirementSet(op, requirementSet);
        }
    }

    private void addSecurityRequirement(org.eclipse.microprofile.openapi.models.Operation op, SecurityRequirement requirement) {
        if (requirement.name().isEmpty()) {
            return;
        }
        var modelRequirement = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class);
        modelRequirement.addScheme(requirement.name(), List.of(requirement.scopes()));
        op.addSecurityRequirement(modelRequirement);
    }

    private void addSecurityRequirementSet(org.eclipse.microprofile.openapi.models.Operation op,
                                           SecurityRequirementsSet requirementSet) {
        if (requirementSet.value().length == 0) {
            op.addSecurityRequirement(OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class));
            return;
        }
        var grouped = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class);
        boolean hasScheme = false;
        for (SecurityRequirement requirement : requirementSet.value()) {
            if (!requirement.name().isEmpty()) {
                grouped.addScheme(requirement.name(), List.of(requirement.scopes()));
                hasScheme = true;
            }
        }
        if (hasScheme) {
            op.addSecurityRequirement(grouped);
        }
    }

    private SecurityRequirementsSet[] collectSecurityRequirementSets(Method method) {
        return method.getAnnotationsByType(SecurityRequirementsSet.class);
    }

    private SecurityRequirementsSet[] collectSecurityRequirementSets(Class<?> resourceClass) {
        return resourceClass.getAnnotationsByType(SecurityRequirementsSet.class);
    }

    private static org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type toModelSecuritySchemeType(SecuritySchemeType type) {
        return switch (type) {
            case APIKEY -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.APIKEY;
            case HTTP -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.HTTP;
            case OAUTH2 -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.OAUTH2;
            case OPENIDCONNECT -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.OPENIDCONNECT;
            case MUTUALTLS -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.MUTUALTLS;
            case DEFAULT -> null;
        };
    }

    private static org.eclipse.microprofile.openapi.models.security.SecurityScheme.In toModelSecuritySchemeIn(SecuritySchemeIn in) {
        return switch (in) {
            case HEADER -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.In.HEADER;
            case QUERY -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.In.QUERY;
            case COOKIE -> org.eclipse.microprofile.openapi.models.security.SecurityScheme.In.COOKIE;
            case DEFAULT -> null;
        };
    }

    private org.eclipse.microprofile.openapi.models.security.OAuthFlows toModelFlows(OAuthFlows flowsAnnotation) {
        if (flowsAnnotation == null) {
            return null;
        }
        org.eclipse.microprofile.openapi.models.security.OAuthFlow implicit = toModelFlow(flowsAnnotation.implicit());
        org.eclipse.microprofile.openapi.models.security.OAuthFlow password = toModelFlow(flowsAnnotation.password());
        org.eclipse.microprofile.openapi.models.security.OAuthFlow clientCredentials = toModelFlow(flowsAnnotation.clientCredentials());
        org.eclipse.microprofile.openapi.models.security.OAuthFlow authorizationCode = toModelFlow(flowsAnnotation.authorizationCode());

        if (implicit == null && password == null && clientCredentials == null && authorizationCode == null) {
            return null;
        }

        org.eclipse.microprofile.openapi.models.security.OAuthFlows flows = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.OAuthFlows.class);
        if (implicit != null) {
            flows.setImplicit(implicit);
        }
        if (password != null) {
            flows.setPassword(password);
        }
        if (clientCredentials != null) {
            flows.setClientCredentials(clientCredentials);
        }
        if (authorizationCode != null) {
            flows.setAuthorizationCode(authorizationCode);
        }
        AnnotationModelMappings.applyExtensions(flows, flowsAnnotation.extensions());
        return flows;
    }

    private org.eclipse.microprofile.openapi.models.security.OAuthFlow toModelFlow(OAuthFlow flowAnnotation) {
        if (flowAnnotation == null) {
            return null;
        }
        boolean hasContent = !flowAnnotation.authorizationUrl().isEmpty()
                || !flowAnnotation.tokenUrl().isEmpty()
                || !flowAnnotation.refreshUrl().isEmpty()
                || flowAnnotation.scopes().length > 0;
        if (!hasContent) {
            return null;
        }
        org.eclipse.microprofile.openapi.models.security.OAuthFlow flow = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.OAuthFlow.class);
        if (!flowAnnotation.authorizationUrl().isEmpty()) {
            flow.setAuthorizationUrl(flowAnnotation.authorizationUrl());
        }
        if (!flowAnnotation.tokenUrl().isEmpty()) {
            flow.setTokenUrl(flowAnnotation.tokenUrl());
        }
        if (!flowAnnotation.refreshUrl().isEmpty()) {
            flow.setRefreshUrl(flowAnnotation.refreshUrl());
        }
        for (OAuthScope scope : flowAnnotation.scopes()) {
            if (!scope.name().isEmpty()) {
                flow.addScope(scope.name(), scope.description());
            }
        }
        AnnotationModelMappings.applyExtensions(flow, flowAnnotation.extensions());
        return flow;
    }

    private void applyContentAnnotation(Content contentAnnotation,
                                        MediaType mediaType,
                                        Type schemaType,
                                        boolean inferSchemaWhenAbsent) {
        if (schemaGenerator != null && hasAnyContent(contentAnnotation.schema())) {
            mediaType.setSchema(schemaGenerator.generate(schemaType, contentAnnotation.schema()));
        } else if (schemaGenerator != null && inferSchemaWhenAbsent && schemaType != null) {
            mediaType.setSchema(schemaGenerator.generate(schemaType));
        }
        if (!contentAnnotation.example().isEmpty()) {
            mediaType.setExample(contentAnnotation.example());
        }
        for (ExampleObject exampleAnnotation : contentAnnotation.examples()) {
            if (exampleAnnotation.name().isEmpty()) {
                continue;
            }
            var example = AnnotationModelMappings.toModelExample(exampleAnnotation);
            mediaType.addExample(exampleAnnotation.name(), example);
        }
        for (Encoding encodingAnnotation : contentAnnotation.encoding()) {
            if (encodingAnnotation.name().isEmpty()) {
                continue;
            }
            var encoding = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Encoding.class);
            if (!encodingAnnotation.contentType().isEmpty()) {
                encoding.setContentType(encodingAnnotation.contentType());
            }
            if (encodingAnnotation.explode()) {
                encoding.setExplode(Boolean.TRUE);
            }
            if (encodingAnnotation.allowReserved()) {
                encoding.setAllowReserved(Boolean.TRUE);
            }
            if (!encodingAnnotation.style().isEmpty()) {
                try {
                    encoding.setStyle(org.eclipse.microprofile.openapi.models.media.Encoding.Style
                            .valueOf(toEncodingStyleName(encodingAnnotation.style())));
                } catch (IllegalArgumentException ignored) {
                    // Ignore unknown style values.
                }
            }
            for (Header headerAnnotation : encodingAnnotation.headers()) {
                String headerName = resolveHeaderName(headerAnnotation);
                if (headerName == null || headerName.isEmpty()) {
                    continue;
                }
                encoding.addHeader(headerName, toModelHeader(headerAnnotation));
            }
            AnnotationModelMappings.applyExtensions(encoding, encodingAnnotation.extensions());
            mediaType.addEncoding(encodingAnnotation.name(), encoding);
        }
        AnnotationModelMappings.applyExtensions(mediaType, contentAnnotation.extensions());
    }

    @SuppressWarnings("removal")
    private org.eclipse.microprofile.openapi.models.headers.Header toModelHeader(Header headerAnnotation) {
        var header = OASFactory.createObject(org.eclipse.microprofile.openapi.models.headers.Header.class);
        if (!headerAnnotation.ref().isEmpty()) {
            header.setRef(headerAnnotation.ref());
        }
        if (!headerAnnotation.description().isEmpty()) {
            header.setDescription(headerAnnotation.description());
        }
        header.setRequired(headerAnnotation.required());
        header.setDeprecated(headerAnnotation.deprecated());
        header.setAllowEmptyValue(headerAnnotation.allowEmptyValue());
        AnnotationModelMappings.applyHeaderExamples(header, headerAnnotation);
        if (schemaGenerator != null && hasAnyContent(headerAnnotation.schema())) {
            header.setSchema(schemaGenerator.generate(Object.class, headerAnnotation.schema()));
        }
        AnnotationModelMappings.applyExtensions(header, headerAnnotation.extensions());
        return header;
    }

    private String resolveHeaderName(Header headerAnnotation) {
        if (!headerAnnotation.name().isEmpty()) {
            return headerAnnotation.name();
        }
        if (!headerAnnotation.ref().isEmpty()) {
            int slash = headerAnnotation.ref().lastIndexOf('/');
            return slash >= 0 ? headerAnnotation.ref().substring(slash + 1) : headerAnnotation.ref();
        }
        return null;
    }

    private String toEncodingStyleName(String styleValue) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < styleValue.length(); i++) {
            char ch = styleValue.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                out.append('_');
            }
            out.append(Character.toUpperCase(ch));
        }
        return out.toString();
    }

    private Extension[] collectStandaloneExtensions(Method method) {
        List<Extension> out = new ArrayList<>(Arrays.asList(method.getAnnotationsByType(Extension.class)));
        Extensions grouped = method.getAnnotation(Extensions.class);
        if (grouped != null) {
            out.addAll(Arrays.asList(grouped.value()));
        }
        return out.toArray(Extension[]::new);
    }

    private String defaultDescriptionForStatus(String responseCode) {
        return switch (responseCode) {
            case "200" -> "OK";
            case "201" -> "Created";
            case "202" -> "Accepted";
            case "204" -> "No Content";
            case "400" -> "Bad Request";
            case "401" -> "Unauthorized";
            case "403" -> "Forbidden";
            case "404" -> "Not Found";
            case "500" -> "Internal Server Error";
            default -> "default".equals(responseCode) ? "Default response" : "Response " + responseCode;
        };
    }
}

