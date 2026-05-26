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
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callbacks;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameters;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.parameters.Parameter.In;
import io.vidocq.grimm.internal.schema.SchemaGenerator;

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
        Path classPath = clazz.getAnnotation(Path.class);
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
            org.eclipse.microprofile.openapi.models.Operation modelOp = buildOperation(method, opAnn, consumes, produces);
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
            Method method, Operation opAnn, String[] consumes, String[] produces) {
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

        // Tags coming from @Operation are not in MP OpenAPI 4.1 Operation annotation members,
        // but @Tag annotations on the method should be added as operation tags (spec §3.5).
        applyMethodTags(method, op);

        processParameters(method, op);
        processRequestBody(method, op, consumes);
        processResponses(method, op, produces);
        processCallbacks(method, op);

        return op;
    }

    private static void applyMethodTags(Method method, org.eclipse.microprofile.openapi.models.Operation op) {
        var tags = method.getAnnotationsByType(org.eclipse.microprofile.openapi.annotations.tags.Tag.class);
        for (var t : tags) {
            if (!t.name().isEmpty()) {
                op.addTag(t.name());
            }
        }
        // @Operation in MP OpenAPI 4.1 has no `tags()` member; we honor @Tag annotations only.
    }

    // ---------- Parameters ----------

    private void processParameters(Method method, org.eclipse.microprofile.openapi.models.Operation op) {
        var paramAnnotations = method.getParameterAnnotations();
        var paramTypes = method.getParameterTypes();
        var paramGenericTypes = method.getGenericParameterTypes();

        // Method-level @Parameters / @Parameter — explicit declarations.
        var methodLevelParameters = method.getAnnotation(Parameters.class);
        if (methodLevelParameters != null) {
            for (Parameter p : methodLevelParameters.value()) {
                addParameter(op, buildExplicitParameter(p, null, null, null));
            }
        }
        for (Parameter p : method.getAnnotationsByType(Parameter.class)) {
            addParameter(op, buildExplicitParameter(p, null, null, null));
        }

        for (int i = 0; i < paramAnnotations.length; i++) {
            Annotation[] anns = paramAnnotations[i];
            Class<?> type = paramTypes[i];
            Type genericType = paramGenericTypes[i];

            if (hasAnnotation(anns, Context.class) || hasAnnotation(anns, FormParam.class)) {
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

            var modelParam = buildExplicitParameter(explicit, inferredName, inferredIn, genericType);
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
            Parameter explicit, String inferredName, In inferredIn, Type sourceType) {
        var p = OASFactory.createObject(org.eclipse.microprofile.openapi.models.parameters.Parameter.class);

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
            if (!explicit.example().isEmpty()) {
                p.setExample(explicit.example());
            }
        }
        // Schema (§3.10): site-level @Schema on the parameter, or inferred from the type.
        if (schemaGenerator != null && sourceType != null) {
            var siteAnn = explicit != null ? explicit.schema() : null;
            // The annotation's default name is empty — treat a zero-content @Schema as absent.
            var schema = (siteAnn != null && hasAnyContent(siteAnn))
                    ? schemaGenerator.generate(sourceType, siteAnn)
                    : schemaGenerator.generate(sourceType);
            p.setSchema(schema);
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
                || s.required()
                || s.enumeration().length > 0;
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
        var paramTypes = method.getParameterTypes();
        var paramGenericTypes = method.getGenericParameterTypes();

        RequestBody explicit = null;
        int entityIndex = -1;

        for (int i = 0; i < paramAnnotations.length; i++) {
            Annotation[] anns = paramAnnotations[i];
            if (hasAnnotation(anns, Context.class)
                    || hasAnnotation(anns, PathParam.class)
                    || hasAnnotation(anns, QueryParam.class)
                    || hasAnnotation(anns, HeaderParam.class)
                    || hasAnnotation(anns, CookieParam.class)
                    || hasAnnotation(anns, FormParam.class)) {
                Parameter pAnn = findAnnotation(anns, Parameter.class);
                // a Parameter annotation does not produce a body; only counts if no JAX-RS param annotation
                if (pAnn != null) {
                    // already handled in processParameters
                }
                continue;
            }
            RequestBody rb = findAnnotation(anns, RequestBody.class);
            if (rb != null) {
                explicit = rb;
                entityIndex = i;
            } else if (entityIndex == -1) {
                // first un-annotated parameter = entity body
                entityIndex = i;
            }
        }

        // Also check method-level @RequestBody
        if (explicit == null) {
            RequestBody methodLevel = method.getAnnotation(RequestBody.class);
            if (methodLevel != null) {
                explicit = methodLevel;
            }
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
            if (explicit.content().length > 0) {
                var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
                for (Content c : explicit.content()) {
                    String mt = c.mediaType().isEmpty() ? "*/*" : c.mediaType();
                    content.addMediaType(mt, OASFactory.createObject(MediaType.class));
                }
                modelBody.setContent(content);
            }
        }
        if (modelBody.getContent() == null) {
            // infer content from @Consumes
            var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
            String[] effective = consumes.length > 0 ? consumes : new String[]{"application/json"};
            Type entityType = (entityIndex >= 0) ? paramGenericTypes[entityIndex] : null;
            for (String mt : effective) {
                MediaType mediaType = OASFactory.createObject(MediaType.class);
                if (schemaGenerator != null && entityType != null) {
                    mediaType.setSchema(schemaGenerator.generate(entityType));
                }
                content.addMediaType(mt, mediaType);
            }
            modelBody.setContent(content);
        }
        op.setRequestBody(modelBody);
    }

    // ---------- Responses ----------

    private void processResponses(Method method, org.eclipse.microprofile.openapi.models.Operation op, String[] produces) {
        var responses = OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponses.class);

        APIResponse[] declared = collectResponses(method);
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
                        String mt = c.mediaType().isEmpty() ? "*/*" : c.mediaType();
                        content.addMediaType(mt, OASFactory.createObject(MediaType.class));
                    }
                    modelResp.setContent(content);
                }
                responses.addAPIResponse(code, modelResp);
            }
        }
        op.setResponses(responses);
    }

    private static APIResponse[] collectResponses(Method method) {
        APIResponses aggregate = method.getAnnotation(APIResponses.class);
        APIResponse single = method.getAnnotation(APIResponse.class);
        List<APIResponse> all = new ArrayList<>();
        if (aggregate != null) {
            all.addAll(Arrays.asList(aggregate.value()));
        }
        if (single != null) {
            all.add(single);
        }
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
            String urlExpr = cb.callbackUrlExpression();
            if (!urlExpr.isEmpty() && cb.operations().length > 0) {
                var pathItem = OASFactory.createObject(PathItem.class);
                for (CallbackOperation co : cb.operations()) {
                    var inner = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Operation.class);
                    if (!co.summary().isEmpty()) inner.setSummary(co.summary());
                    if (!co.description().isEmpty()) inner.setDescription(co.description());
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
}

