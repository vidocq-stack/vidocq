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
package io.vidocq.grimm.internal.merger;

import io.vidocq.grimm.internal.model.AbstractExtensibleRef;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.Extensible;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.Reference;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merges multiple {@link ModelSource} instances according to spec priority.
 *
 * <p>Spec "Processing rules": the {@code OASModelReader} builds the starting model, the static
 * file overrides its conflicting elements, then the annotations override any conflicting element
 * of both — priority annotations &gt; static file &gt; OASModelReader.</p>
 *
 * This merger uses pattern matching on the sealed {@link ModelSource} interface.
 */
public final class ModelMerger {

    /**
     * Merges the given sources in priority order.
     *
     * @param sources the sources to merge (should include StaticFileSource, AnnotationSource,
     *                ReaderSource in any order)
     * @return the merged OpenAPI model
     */
    public OpenAPI merge(List<ModelSource> sources) {
        Objects.requireNonNull(sources, "sources must not be null");

        // Extract sources by type
        OpenAPI staticModel = null;
        OpenAPI readerModel = null;
        OpenAPI annotationModel = null;

        for (ModelSource source : sources) {
            if (source instanceof StaticFileSource staticSource) {
                staticModel = staticSource.getModel();
            } else if (source instanceof ReaderSource readerSource) {
                readerModel = readerSource.getModel();
            } else if (source instanceof AnnotationSource annotationSource) {
                annotationModel = annotationSource.getModel();
            }
        }

        // Start with an empty model
        OpenAPI result = OASFactory.createObject(OpenAPI.class);

        // Processing order per spec: reader → static file → annotations
        if (readerModel != null) {
            mergeReader(result, readerModel);
        }
        if (staticModel != null) {
            mergeStaticFile(result, staticModel);
        }
        if (annotationModel != null) {
            mergeAnnotations(result, annotationModel);
        }

        return result;
    }

    /**
     * Merges a reader model (lowest priority, baseline: the starting model of the spec).
     */
    private void mergeReader(OpenAPI target, OpenAPI source) {
        mergeTopLevel(target, source, false);
    }

    /**
     * Merges a static file model (medium priority, overrides the reader model).
     */
    private void mergeStaticFile(OpenAPI target, OpenAPI source) {
        mergeTopLevel(target, source, true);
    }

    /**
     * Merges annotation model (highest priority, overrides everything).
     */
    private void mergeAnnotations(OpenAPI target, OpenAPI source) {
        mergeTopLevel(target, source, true);
    }

    private void mergeTopLevel(OpenAPI target, OpenAPI source, boolean overwrite) {
        if (source == null) {
            return;
        }
        if (source.getOpenapi() != null && (overwrite || target.getOpenapi() == null)) {
            target.setOpenapi(source.getOpenapi());
        }
        if (source.getInfo() != null && (overwrite || target.getInfo() == null)) {
            target.setInfo(source.getInfo());
        }
        if (source.getExternalDocs() != null && (overwrite || target.getExternalDocs() == null)) {
            target.setExternalDocs(source.getExternalDocs());
        }
        if (source.getJsonSchemaDialect() != null && (overwrite || target.getJsonSchemaDialect() == null)) {
            target.setJsonSchemaDialect(source.getJsonSchemaDialect());
        }

        mergeExtensions(target, source, overwrite);

        if (source.getPaths() != null) {
            if (target.getPaths() == null) {
                target.setPaths(source.getPaths());
            } else {
                mergePaths(target.getPaths(), source.getPaths());
            }
        }

        if (source.getWebhooks() != null && !source.getWebhooks().isEmpty()) {
            Map<String, PathItem> merged = new LinkedHashMap<>();
            if (target.getWebhooks() != null) {
                merged.putAll(target.getWebhooks());
            }
            for (Map.Entry<String, PathItem> entry : source.getWebhooks().entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), this::mergePathItem);
            }
            target.setWebhooks(merged);
        }

        if (source.getComponents() != null) {
            if (target.getComponents() == null) {
                target.setComponents(source.getComponents());
            } else {
                mergeComponents(target.getComponents(), source.getComponents(), overwrite);
            }
        }

        if (source.getServers() != null && !source.getServers().isEmpty()) {
            mergeServers(target, source, overwrite);
        }
        if (source.getSecurity() != null && !source.getSecurity().isEmpty()) {
            if (overwrite || target.getSecurity() == null || target.getSecurity().isEmpty()) {
                target.setSecurity(source.getSecurity());
            }
        }
        if (source.getTags() != null && !source.getTags().isEmpty()) {
            mergeTags(target, source.getTags(), overwrite);
        }
    }

    /**
     * Merges {@code source} path items into {@code target}.
     *
     * @param overwriteOnConflict if {@code true}, source path items override existing ones in
     *                            target (used by reader/annotation merges); if {@code false},
     *                            existing target items are preserved (static file merges).
     */
    private void mergePaths(Paths target, Paths source) {
        if (source.getPathItems() != null) {
            for (Map.Entry<String, PathItem> e : source.getPathItems().entrySet()) {
                String path = e.getKey();
                PathItem sourceItem = e.getValue();
                if (!target.hasPathItem(path)) {
                    target.addPathItem(path, sourceItem);
                } else {
                    target.addPathItem(path, mergePathItem(target.getPathItem(path), sourceItem));
                }
            }
        }
    }

    private PathItem mergePathItem(PathItem target, PathItem source) {
        if (target == null) return source;
        if (source == null) return target;

        if (source.getSummary() != null) {
            target.setSummary(source.getSummary());
        }
        if (source.getDescription() != null) {
            target.setDescription(source.getDescription());
        }
        mergeReference(target, source);
        mergeExtensions(target, source, true);

        target.setParameters(mergeParameters(target.getParameters(), source.getParameters()));
        target.setServers(mergeServerLists(target.getServers(), source.getServers(), true));

        for (PathItem.HttpMethod method : PathItem.HttpMethod.values()) {
            Operation sourceOp = operationOf(source, method);
            if (sourceOp == null) {
                continue;
            }
            Operation targetOp = operationOf(target, method);
            setOperation(target, method, targetOp == null ? sourceOp : mergeOperation(targetOp, sourceOp));
        }

        return target;
    }

    private Operation mergeOperation(Operation target, Operation source) {
        if (source.getSummary() != null) target.setSummary(source.getSummary());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getExternalDocs() != null) target.setExternalDocs(source.getExternalDocs());
        if (source.getOperationId() != null) target.setOperationId(source.getOperationId());
        if (source.getDeprecated() != null) target.setDeprecated(source.getDeprecated());

        mergeExtensions(target, source, true);

        target.setTags(mergeStringList(target.getTags(), source.getTags()));
        target.setParameters(mergeParameters(target.getParameters(), source.getParameters()));
        target.setServers(mergeServerLists(target.getServers(), source.getServers(), true));

        if (source.getRequestBody() != null) {
            target.setRequestBody(target.getRequestBody() == null
                    ? source.getRequestBody()
                    : mergeRequestBody(target.getRequestBody(), source.getRequestBody()));
        }

        if (source.getResponses() != null) {
            target.setResponses(target.getResponses() == null
                    ? source.getResponses()
                    : mergeApiResponses(target.getResponses(), source.getResponses()));
        }

        if (source.getCallbacks() != null && !source.getCallbacks().isEmpty()) {
            Map<String, Callback> merged = new LinkedHashMap<>();
            if (target.getCallbacks() != null) {
                merged.putAll(target.getCallbacks());
            }
            merged.putAll(source.getCallbacks());
            target.setCallbacks(merged);
        }
        if (source.getSecurity() != null && !source.getSecurity().isEmpty()) {
            target.setSecurity(source.getSecurity());
        }
        return target;
    }

    private RequestBody mergeRequestBody(RequestBody target, RequestBody source) {
        if (source.getDescription() != null) {
            target.setDescription(source.getDescription());
        }
        if (source.getRequired() != null) {
            target.setRequired(source.getRequired());
        }
        mergeReference(target, source);
        mergeExtensions(target, source, true);
        if (source.getContent() != null) {
            target.setContent(target.getContent() == null
                    ? source.getContent()
                    : mergeContent(target.getContent(), source.getContent()));
        }
        return target;
    }

    private APIResponses mergeApiResponses(APIResponses target, APIResponses source) {
        Map<String, APIResponse> merged = new LinkedHashMap<>();
        if (target.getAPIResponses() != null) {
            merged.putAll(target.getAPIResponses());
        }
        if (source.getAPIResponses() != null) {
            for (Map.Entry<String, APIResponse> e : source.getAPIResponses().entrySet()) {
                merged.merge(e.getKey(), e.getValue(), this::mergeApiResponse);
            }
        }
        target.setAPIResponses(merged);
        mergeExtensions(target, source, true);
        return target;
    }

    private APIResponse mergeApiResponse(APIResponse target, APIResponse source) {
        if (target == null) return source;
        if (source == null) return target;

        if (source.getDescription() != null) {
            target.setDescription(source.getDescription());
        }
        mergeReference(target, source);
        if (source.getContent() != null) {
            target.setContent(target.getContent() == null
                    ? source.getContent()
                    : mergeContent(target.getContent(), source.getContent()));
        }
        mergeMap(target.getHeaders(), source.getHeaders(), target::setHeaders, true);
        mergeMap(target.getLinks(), source.getLinks(), target::setLinks, true);
        mergeExtensions(target, source, true);
        return target;
    }

    private Content mergeContent(Content target, Content source) {
        Map<String, MediaType> merged = new LinkedHashMap<>();
        if (target.getMediaTypes() != null) {
            merged.putAll(target.getMediaTypes());
        }
        if (source.getMediaTypes() != null) {
            for (Map.Entry<String, MediaType> entry : source.getMediaTypes().entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), this::mergeMediaType);
            }
        }
        target.setMediaTypes(merged);
        return target;
    }

    private MediaType mergeMediaType(MediaType target, MediaType source) {
        if (target == null) return source;
        if (source == null) return target;

        if (source.getSchema() != null) {
            target.setSchema(target.getSchema() == null
                    ? source.getSchema()
                    : mergeSchema(target.getSchema(), source.getSchema()));
        }
        if (source.getExample() != null) {
            target.setExample(source.getExample());
        }
        if (source.getExamples() != null && !source.getExamples().isEmpty()) {
            mergeMap(target.getExamples(), source.getExamples(), target::setExamples, true);
        }
        if (source.getEncoding() != null && !source.getEncoding().isEmpty()) {
            mergeMap(target.getEncoding(), source.getEncoding(), target::setEncoding, true);
        }
        mergeExtensions(target, source, true);
        return target;
    }

    /**
     * The keywords whose empty value leaves the lower source's value in place, as the merge has
     * always done for them. Any other property, extensions included, is copied even when empty:
     * {@code default: []} or {@code const: {}} is a value.
     */
    private static final Set<String> KEPT_WHEN_EMPTY = Set.of("type", "enum", "required", "properties");

    /**
     * Every property the higher-priority {@code source} sets wins, extensions included: the
     * properties are those of {@link Schema#getAll()}, named as in the JSON document, so the merge
     * follows the property table of the model and a property added to it cannot be forgotten
     * here. {@code items} and {@code properties} merge recursively; an empty {@code type},
     * {@code enum}, {@code required} or {@code properties} does not override what the lower source
     * says. {@code $ref} is copied as written: {@code set("$ref")} does not expand a short name.
     */
    private Schema mergeSchema(Schema target, Schema source) {
        for (Map.Entry<String, ?> property : source.getAll().entrySet()) {
            String name = property.getKey();
            Object value = property.getValue();
            if ("items".equals(name) && value instanceof Schema items && target.getItems() != null) {
                target.setItems(mergeSchema(target.getItems(), items));
            } else if ("properties".equals(name) && source.getProperties() != null
                    && !source.getProperties().isEmpty()) {
                Map<String, Schema> merged = new LinkedHashMap<>();
                if (target.getProperties() != null) {
                    merged.putAll(target.getProperties());
                }
                for (Map.Entry<String, Schema> entry : source.getProperties().entrySet()) {
                    merged.merge(entry.getKey(), entry.getValue(), this::mergeSchema);
                }
                target.setProperties(merged);
            } else if (!(KEPT_WHEN_EMPTY.contains(name) && isEmpty(value))) {
                target.set(name, value);
            }
        }
        return target;
    }

    private static boolean isEmpty(Object value) {
        return value instanceof java.util.Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private List<Parameter> mergeParameters(List<Parameter> base, List<Parameter> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return base;
        }
        Map<String, Parameter> byKey = new LinkedHashMap<>();
        if (base != null) {
            for (Parameter p : base) {
                byKey.put(parameterKey(p), p);
            }
        }
        for (Parameter p : incoming) {
            byKey.put(parameterKey(p), p);
        }
        return new ArrayList<>(byKey.values());
    }

    private String parameterKey(Parameter parameter) {
        String name = parameter != null ? parameter.getName() : "";
        String in = parameter != null && parameter.getIn() != null ? parameter.getIn().name() : "";
        return in + ":" + name;
    }

    private void mergeComponents(Components target, Components source, boolean overwrite) {
        mergeMap(target.getSchemas(), source.getSchemas(), target::setSchemas, overwrite);
        mergeMap(target.getResponses(), source.getResponses(), target::setResponses, overwrite);
        mergeMap(target.getParameters(), source.getParameters(), target::setParameters, overwrite);
        mergeMap(target.getExamples(), source.getExamples(), target::setExamples, overwrite);
        mergeMap(target.getRequestBodies(), source.getRequestBodies(), target::setRequestBodies, overwrite);
        mergeMap(target.getHeaders(), source.getHeaders(), target::setHeaders, overwrite);
        mergeMap(target.getSecuritySchemes(), source.getSecuritySchemes(), target::setSecuritySchemes, overwrite);
        mergeMap(target.getLinks(), source.getLinks(), target::setLinks, overwrite);
        mergeCallbacksMap(target.getCallbacks(), source.getCallbacks(), target::setCallbacks, overwrite);
        mergeMap(target.getPathItems(), source.getPathItems(), target::setPathItems, overwrite);
        mergeExtensions(target, source, overwrite);
    }

    private void mergeCallbacksMap(Map<String, Callback> target,
                                   Map<String, Callback> source,
                                   java.util.function.Consumer<Map<String, Callback>> setter,
                                   boolean overwrite) {
        if (source == null || source.isEmpty()) {
            return;
        }
        Map<String, Callback> merged = new LinkedHashMap<>();
        if (target != null) {
            merged.putAll(target);
        }
        for (Map.Entry<String, Callback> entry : source.entrySet()) {
            if (!overwrite && merged.containsKey(entry.getKey())) {
                continue;
            }
            merged.merge(entry.getKey(), entry.getValue(), this::mergeCallback);
        }
        setter.accept(merged);
    }

    private Callback mergeCallback(Callback target, Callback source) {
        if (target == null) return source;
        if (source == null) return target;

        if (source.getRef() != null) {
            AbstractExtensibleRef.setVerbatimRef(target, source.getRef());
        }
        if (source.getPathItems() != null && !source.getPathItems().isEmpty()) {
            Map<String, PathItem> merged = new LinkedHashMap<>();
            if (target.getPathItems() != null) {
                merged.putAll(target.getPathItems());
            }
            for (Map.Entry<String, PathItem> entry : source.getPathItems().entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), this::mergePathItem);
            }
            target.setPathItems(merged);
        }
        mergeExtensions(target, source, true);
        return target;
    }

    private <T> void mergeMap(Map<String, T> target,
                              Map<String, T> source,
                              java.util.function.Consumer<Map<String, T>> setter,
                              boolean overwrite) {
        if (source == null || source.isEmpty()) {
            return;
        }
        Map<String, T> merged = new LinkedHashMap<>();
        if (target != null) {
            merged.putAll(target);
        }
        for (Map.Entry<String, T> entry : source.entrySet()) {
            if (overwrite || !merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        setter.accept(merged);
    }

    private void mergeServers(OpenAPI target, OpenAPI source, boolean overwrite) {
        target.setServers(mergeServerLists(target.getServers(), source.getServers(), overwrite));
    }

    private List<Server> mergeServerLists(List<Server> base, List<Server> incoming, boolean overwrite) {
        if (incoming == null || incoming.isEmpty()) {
            return base;
        }
        Map<String, Server> byUrl = new LinkedHashMap<>();
        if (base != null) {
            for (Server s : base) {
                byUrl.put(s.getUrl(), s);
            }
        }
        for (Server s : incoming) {
            Server existing = byUrl.get(s.getUrl());
            if (existing == null) {
                byUrl.put(s.getUrl(), s);
            } else if (overwrite) {
                if (s.getDescription() != null) existing.setDescription(s.getDescription());
                if (s.getVariables() != null && !s.getVariables().isEmpty()) {
                    Map<String, ServerVariable> mergedVars = new LinkedHashMap<>();
                    if (existing.getVariables() != null) {
                        mergedVars.putAll(existing.getVariables());
                    }
                    mergedVars.putAll(s.getVariables());
                    existing.setVariables(mergedVars);
                }
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    private void mergeTags(OpenAPI target, List<Tag> sourceTags, boolean overwrite) {
        Map<String, Tag> merged = new LinkedHashMap<>();
        if (target.getTags() != null) {
            for (Tag tag : target.getTags()) {
                merged.put(tag.getName(), tag);
            }
        }
        for (Tag sourceTag : sourceTags) {
            Tag existing = merged.get(sourceTag.getName());
            if (existing == null) {
                merged.put(sourceTag.getName(), sourceTag);
                continue;
            }
            if (overwrite) {
                if (sourceTag.getDescription() != null) {
                    existing.setDescription(sourceTag.getDescription());
                }
                if (sourceTag.getExternalDocs() != null) {
                    existing.setExternalDocs(sourceTag.getExternalDocs());
                }
                mergeExtensions(existing, sourceTag, true);
            }
        }
        target.setTags(new ArrayList<>(merged.values()));
    }

    private List<String> mergeStringList(List<String> base, List<String> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return base;
        }
        List<String> out = new ArrayList<>();
        if (base != null) {
            for (String item : base) {
                if (!out.contains(item)) {
                    out.add(item);
                }
            }
        }
        for (String item : incoming) {
            if (!out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private void mergeReference(Reference<?> target, Reference<?> source) {
        if (source.getRef() != null) {
            AbstractExtensibleRef.setVerbatimRef(target, source.getRef());
        }
    }

    private void mergeExtensions(Extensible<?> target, Extensible<?> source, boolean overwrite) {
        if (source.getExtensions() == null || source.getExtensions().isEmpty()) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (target.getExtensions() != null) {
            merged.putAll(target.getExtensions());
        }
        for (Map.Entry<String, Object> entry : source.getExtensions().entrySet()) {
            if (overwrite || !merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        target.setExtensions(merged);
    }

    private Operation operationOf(PathItem item, PathItem.HttpMethod method) {
        return switch (method) {
            case GET -> item.getGET();
            case PUT -> item.getPUT();
            case POST -> item.getPOST();
            case DELETE -> item.getDELETE();
            case OPTIONS -> item.getOPTIONS();
            case HEAD -> item.getHEAD();
            case PATCH -> item.getPATCH();
            case TRACE -> item.getTRACE();
        };
    }

    private void setOperation(PathItem item, PathItem.HttpMethod method, Operation operation) {
        switch (method) {
            case GET -> item.setGET(operation);
            case PUT -> item.setPUT(operation);
            case POST -> item.setPOST(operation);
            case DELETE -> item.setDELETE(operation);
            case OPTIONS -> item.setOPTIONS(operation);
            case HEAD -> item.setHEAD(operation);
            case PATCH -> item.setPATCH(operation);
            case TRACE -> item.setTRACE(operation);
        }
    }
}





