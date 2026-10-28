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
package io.vidocq.grimm.internal.serialization;

import io.vidocq.grimm.internal.model.AbstractExtensibleRef;
import io.vidocq.grimm.internal.model.DiscriminatorImpl;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.info.Contact;
import org.eclipse.microprofile.openapi.models.info.License;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Discriminator;
import org.eclipse.microprofile.openapi.models.media.Encoding;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.XML;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.OAuthFlow;
import org.eclipse.microprofile.openapi.models.security.OAuthFlows;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.tags.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class OpenApiModelMapper {

    private static final String EXTERNAL_DOCS = "externalDocs";
    private static final String SERVERS = "servers";
    private static final String SUMMARY = "summary";
    private static final String DESCRIPTION = "description";
    private static final String PARAMETERS = "parameters";
    private static final String DEPRECATED = "deprecated";
    private static final String CONTENT = "content";
    private static final String HEADERS = "headers";
    private static final String EXAMPLES = "examples";
    private static final String REQUIRED = "required";
    private static final String STYLE = "style";
    private static final String EXPLODE = "explode";
    private static final String SCHEMA = "schema";
    private static final String EXAMPLE = "example";

    private OpenApiModelMapper() {
    }

    static OpenAPI toOpenApi(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("OpenAPI document must be a JSON/YAML object");
        }

        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "openapi" -> openAPI.setOpenapi(asString(value));
                case "info" -> openAPI.setInfo(toInfo(value));
                case EXTERNAL_DOCS -> openAPI.setExternalDocs(toExternalDocs(value));
                case SERVERS -> openAPI.setServers(toServers(value));
                case "security" -> openAPI.setSecurity(toSecurity(value));
                case "tags" -> openAPI.setTags(toTags(value));
                case "paths" -> openAPI.setPaths(toPaths(value));
                case "webhooks" -> openAPI.setWebhooks(toPathItemsMap(value));
                case "components" -> openAPI.setComponents(toComponents(value));
                default -> {
                    if (key.startsWith("x-")) {
                        openAPI.addExtension(key, value);
                    }
                }
            }
        }
        return openAPI;
    }

    private static Info toInfo(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Info info = OASFactory.createObject(Info.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "title" -> info.setTitle(asString(value));
                case "version" -> info.setVersion(asString(value));
                case SUMMARY -> info.setSummary(asString(value));
                case DESCRIPTION -> info.setDescription(asString(value));
                case "termsOfService" -> info.setTermsOfService(asString(value));
                case "contact" -> info.setContact(toContact(value));
                case "license" -> info.setLicense(toLicense(value));
                default -> {
                    if (key.startsWith("x-")) {
                        info.addExtension(key, value);
                    }
                }
            }
        }
        return info;
    }

    private static Contact toContact(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Contact contact = OASFactory.createObject(Contact.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "name" -> contact.setName(asString(value));
                case "url" -> contact.setUrl(asString(value));
                case "email" -> contact.setEmail(asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        contact.addExtension(key, value);
                    }
                }
            }
        }
        return contact;
    }

    private static License toLicense(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        License license = OASFactory.createObject(License.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "name" -> license.setName(asString(value));
                case "url" -> license.setUrl(asString(value));
                case "identifier" -> license.setIdentifier(asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        license.addExtension(key, value);
                    }
                }
            }
        }
        return license;
    }

    private static Paths toPaths(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Paths paths = OASFactory.createObject(Paths.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (key.startsWith("x-")) {
                paths.addExtension(key, value);
                continue;
            }
            paths.addPathItem(key, toPathItem(value));
        }
        return paths;
    }

    private static Map<String, PathItem> toPathItemsMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, PathItem> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("x-")) {
                continue;
            }
            PathItem item = toPathItem(entry.getValue());
            if (item != null) {
                result.put(key, item);
            }
        }
        return result;
    }

    private static ExternalDocumentation toExternalDocs(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        ExternalDocumentation externalDocs = OASFactory.createObject(ExternalDocumentation.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "url" -> externalDocs.setUrl(asString(value));
                case DESCRIPTION -> externalDocs.setDescription(asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        externalDocs.addExtension(key, value);
                    }
                }
            }
        }
        return externalDocs;
    }

    private static List<Server> toServers(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        ArrayList<Server> servers = new ArrayList<>(list.size());
        for (Object item : list) {
            Server server = toServer(item);
            if (server != null) {
                servers.add(server);
            }
        }
        return servers;
    }

    private static Server toServer(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Server server = OASFactory.createObject(Server.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "url" -> server.setUrl(asString(value));
                case DESCRIPTION -> server.setDescription(asString(value));
                case "variables" -> server.setVariables(toServerVariables(value));
                default -> {
                    if (key.startsWith("x-")) {
                        server.addExtension(key, value);
                    }
                }
            }
        }
        return server;
    }

    private static List<SecurityRequirement> toSecurity(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        ArrayList<SecurityRequirement> requirements = new ArrayList<>(list.size());
        for (Object item : list) {
            SecurityRequirement requirement = toSecurityRequirement(item);
            if (requirement != null) {
                requirements.add(requirement);
            }
        }
        return requirements;
    }

    private static SecurityRequirement toSecurityRequirement(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        SecurityRequirement requirement = OASFactory.createObject(SecurityRequirement.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String schemeName = String.valueOf(entry.getKey());
            Object rawScopes = entry.getValue();
            if (rawScopes instanceof List<?> scopes) {
                ArrayList<String> scopeNames = new ArrayList<>(scopes.size());
                for (Object scope : scopes) {
                    scopeNames.add(asString(scope));
                }
                requirement.addScheme(schemeName, scopeNames);
            }
        }
        return requirement;
    }

    private static List<Tag> toTags(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        ArrayList<Tag> tags = new ArrayList<>(list.size());
        for (Object item : list) {
            Tag tag = toTag(item);
            if (tag != null) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private static Tag toTag(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Tag tag = OASFactory.createObject(Tag.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "name" -> tag.setName(asString(value));
                case DESCRIPTION -> tag.setDescription(asString(value));
                case EXTERNAL_DOCS -> tag.setExternalDocs(toExternalDocs(value));
                default -> {
                    if (key.startsWith("x-")) {
                        tag.addExtension(key, value);
                    }
                }
            }
        }
        return tag;
    }

    private static PathItem toPathItem(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        PathItem pathItem = OASFactory.createObject(PathItem.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case SUMMARY -> pathItem.setSummary(asString(value));
                case DESCRIPTION -> pathItem.setDescription(asString(value));
                case "$ref" -> AbstractExtensibleRef.setVerbatimRef(pathItem, asString(value));
                case SERVERS -> pathItem.setServers(toServers(value));
                case PARAMETERS -> pathItem.setParameters(toParameters(value));
                case "get" -> pathItem.setGET(toOperation(value));
                case "put" -> pathItem.setPUT(toOperation(value));
                case "post" -> pathItem.setPOST(toOperation(value));
                case "delete" -> pathItem.setDELETE(toOperation(value));
                case "patch" -> pathItem.setPATCH(toOperation(value));
                case "head" -> pathItem.setHEAD(toOperation(value));
                case "options" -> pathItem.setOPTIONS(toOperation(value));
                case "trace" -> pathItem.setTRACE(toOperation(value));
                default -> {
                    if (key.startsWith("x-")) {
                        pathItem.addExtension(key, value);
                    }
                }
            }
        }
        return pathItem;
    }

    private static Operation toOperation(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Operation operation = OASFactory.createObject(Operation.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "operationId" -> operation.setOperationId(asString(value));
                case SUMMARY -> operation.setSummary(asString(value));
                case DESCRIPTION -> operation.setDescription(asString(value));
                case PARAMETERS -> operation.setParameters(toParameters(value));
                case "requestBody" -> operation.setRequestBody(toRequestBody(value));
                case "responses" -> operation.setResponses(toApiResponses(value));
                case "callbacks" -> operation.setCallbacks(toCallbacks(value));
                case "tags" -> operation.setTags(toStringList(value));
                case DEPRECATED -> operation.setDeprecated(asBoolean(value));
                case SERVERS -> operation.setServers(toServers(value));
                case EXTERNAL_DOCS -> operation.setExternalDocs(toExternalDocs(value));
                case "security" -> operation.setSecurity(toSecurity(value));
                default -> {
                    if (key.startsWith("x-")) {
                        operation.addExtension(key, value);
                    }
                }
            }
        }
        return operation;
    }

    private static APIResponses toApiResponses(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        APIResponses responses = OASFactory.createObject(APIResponses.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (key.startsWith("x-")) {
                responses.addExtension(key, value);
            } else {
                responses.addAPIResponse(key, toApiResponse(value));
            }
        }
        return responses;
    }

    private static APIResponse toApiResponse(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        APIResponse response = OASFactory.createObject(APIResponse.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (DESCRIPTION.equals(key)) {
                response.setDescription(asString(value));
            } else if ("$ref".equals(key)) {
                AbstractExtensibleRef.setVerbatimRef(response, asString(value));
            } else if (CONTENT.equals(key)) {
                response.setContent(toContent(value));
            } else if (HEADERS.equals(key)) {
                response.setHeaders(toHeaders(value));
            } else if ("links".equals(key)) {
                response.setLinks(toLinks(value));
            } else if (key.startsWith("x-")) {
                response.addExtension(key, value);
            }
        }
        return response;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Components toComponents(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Components components = OASFactory.createObject(Components.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case HEADERS -> components.setHeaders(toHeaders(value));
                case "schemas" -> components.setSchemas(toSchemas(value));
                case PARAMETERS -> components.setParameters(toParametersMap(value));
                case "requestBodies" -> components.setRequestBodies(toRequestBodies(value));
                case "responses" -> components.setResponses(toResponsesMap(value));
                case EXAMPLES -> components.setExamples(toExamples(value));
                case "securitySchemes" -> components.setSecuritySchemes(toSecuritySchemes(value));
                case "links" -> components.setLinks(toLinks(value));
                case "callbacks" -> components.setCallbacks(toCallbacks(value));
                case "pathItems" -> components.setPathItems(toPathItemsMap(value));
                default -> {
                    if (key.startsWith("x-")) {
                        components.addExtension(key, value);
                    }
                }
            }
        }
        return components;
    }

    private static Map<String, ServerVariable> toServerVariables(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, ServerVariable> variables = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            ServerVariable variable = toServerVariable(entry.getValue());
            if (variable != null) {
                variables.put(String.valueOf(entry.getKey()), variable);
            }
        }
        return variables;
    }

    private static ServerVariable toServerVariable(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        ServerVariable variable = OASFactory.createObject(ServerVariable.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case DESCRIPTION -> variable.setDescription(asString(value));
                case "default" -> variable.setDefaultValue(asString(value));
                case "enum" -> variable.setEnumeration(toStringList(value));
                default -> {
                    if (key.startsWith("x-")) {
                        variable.addExtension(key, value);
                    }
                }
            }
        }
        return variable;
    }

    private static List<Parameter> toParameters(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        ArrayList<Parameter> parameters = new ArrayList<>(list.size());
        for (Object item : list) {
            Parameter parameter = toParameter(item);
            if (parameter != null) {
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    private static Parameter toParameter(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Parameter parameter = OASFactory.createObject(Parameter.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "name" -> parameter.setName(asString(value));
                case "in" -> parameter.setIn(toParameterIn(asString(value)));
                case REQUIRED -> parameter.setRequired(asBoolean(value));
                case DESCRIPTION -> parameter.setDescription(asString(value));
                case DEPRECATED -> parameter.setDeprecated(asBoolean(value));
                case "allowEmptyValue" -> parameter.setAllowEmptyValue(asBoolean(value));
                case STYLE -> parameter.setStyle(toParameterStyle(asString(value)));
                case EXPLODE -> parameter.setExplode(asBoolean(value));
                case "allowReserved" -> parameter.setAllowReserved(asBoolean(value));
                case SCHEMA -> parameter.setSchema(toSchema(value));
                case EXAMPLE -> parameter.setExample(value);
                case EXAMPLES -> parameter.setExamples(toExamples(value));
                case CONTENT -> parameter.setContent(toContent(value));
                case "$ref" -> AbstractExtensibleRef.setVerbatimRef(parameter, asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        parameter.addExtension(key, value);
                    }
                }
            }
        }
        return parameter;
    }

    private static Parameter.In toParameterIn(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "query" -> Parameter.In.QUERY;
            case "header" -> Parameter.In.HEADER;
            case "path" -> Parameter.In.PATH;
            case "cookie" -> Parameter.In.COOKIE;
            default -> null;
        };
    }

    private static Parameter.Style toParameterStyle(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "matrix" -> Parameter.Style.MATRIX;
            case "label" -> Parameter.Style.LABEL;
            case "form" -> Parameter.Style.FORM;
            case "simple" -> Parameter.Style.SIMPLE;
            case "spaceDelimited" -> Parameter.Style.SPACEDELIMITED;
            case "pipeDelimited" -> Parameter.Style.PIPEDELIMITED;
            case "deepObject" -> Parameter.Style.DEEPOBJECT;
            default -> null;
        };
    }

    private static RequestBody toRequestBody(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        RequestBody requestBody = OASFactory.createObject(RequestBody.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case DESCRIPTION -> requestBody.setDescription(asString(value));
                case REQUIRED -> requestBody.setRequired(asBoolean(value));
                case CONTENT -> requestBody.setContent(toContent(value));
                case "$ref" -> AbstractExtensibleRef.setVerbatimRef(requestBody, asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        requestBody.addExtension(key, value);
                    }
                }
            }
        }
        return requestBody;
    }

    private static Map<String, Callback> toCallbacks(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, Callback> callbacks = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Callback callback = toCallback(entry.getValue());
            if (callback != null) {
                callbacks.put(String.valueOf(entry.getKey()), callback);
            }
        }
        return callbacks;
    }

    private static Callback toCallback(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Callback callback = OASFactory.createObject(Callback.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String expression = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if ("$ref".equals(expression)) {
                AbstractExtensibleRef.setVerbatimRef(callback, asString(value));
            } else if (expression.startsWith("x-")) {
                callback.addExtension(expression, value);
            } else {
                callback.addPathItem(expression, toPathItem(value));
            }
        }
        return callback;
    }

    private static Content toContent(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Content content = OASFactory.createObject(Content.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String mediaTypeName = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (!mediaTypeName.startsWith("x-")) {
                content.addMediaType(mediaTypeName, toMediaType(value));
            }
        }
        return content;
    }

    private static MediaType toMediaType(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        MediaType mediaType = OASFactory.createObject(MediaType.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case SCHEMA -> mediaType.setSchema(toSchema(value));
                case EXAMPLE -> mediaType.setExample(value);
                case EXAMPLES -> mediaType.setExamples(toExamples(value));
                case "encoding" -> mediaType.setEncoding(toEncodingMap(value));
                default -> {
                    if (key.startsWith("x-")) {
                        mediaType.addExtension(key, value);
                    }
                }
            }
        }
        return mediaType;
    }

    private static Map<String, Header> toHeaders(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, Header> headers = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Header header = toHeader(entry.getValue());
            if (header != null) {
                headers.put(String.valueOf(entry.getKey()), header);
            }
        }
        return headers;
    }

    @SuppressWarnings("removal")
    private static Header toHeader(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Header header = OASFactory.createObject(Header.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case DESCRIPTION -> header.setDescription(asString(value));
                case REQUIRED -> header.setRequired(asBoolean(value));
                case DEPRECATED -> header.setDeprecated(asBoolean(value));
                case "allowEmptyValue" -> header.setAllowEmptyValue(asBoolean(value));
                case STYLE -> header.setStyle(toHeaderStyle(asString(value)));
                case EXPLODE -> header.setExplode(asBoolean(value));
                case CONTENT -> header.setContent(toContent(value));
                case EXAMPLE -> header.setExample(value);
                case EXAMPLES -> header.setExamples(toExamples(value));
                case SCHEMA -> header.setSchema(toSchema(value));
                case "$ref" -> AbstractExtensibleRef.setVerbatimRef(header, asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        header.addExtension(key, value);
                    }
                }
            }
        }
        return header;
    }

    private static Header.Style toHeaderStyle(String raw) {
        // "simple" is the only style a header allows.
        return "simple".equals(raw) ? Header.Style.SIMPLE : null;
    }

    private static Map<String, Parameter> toParametersMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, Parameter> parameters = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Parameter parameter = toParameter(entry.getValue());
            if (parameter != null) {
                parameters.put(String.valueOf(entry.getKey()), parameter);
            }
        }
        return parameters;
    }

    private static Map<String, RequestBody> toRequestBodies(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, RequestBody> requestBodies = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            RequestBody body = toRequestBody(entry.getValue());
            if (body != null) {
                requestBodies.put(String.valueOf(entry.getKey()), body);
            }
        }
        return requestBodies;
    }

    private static Map<String, APIResponse> toResponsesMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, APIResponse> responses = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            APIResponse response = toApiResponse(entry.getValue());
            if (response != null) {
                responses.put(String.valueOf(entry.getKey()), response);
            }
        }
        return responses;
    }

    private static Map<String, Example> toExamples(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, Example> examples = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Example example = toExample(entry.getValue());
            if (example != null) {
                examples.put(String.valueOf(entry.getKey()), example);
            }
        }
        return examples;
    }

    private static Example toExample(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Example example = OASFactory.createObject(Example.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case SUMMARY -> example.setSummary(asString(value));
                case DESCRIPTION -> example.setDescription(asString(value));
                case "value" -> example.setValue(value);
                case "externalValue" -> example.setExternalValue(asString(value));
                case "$ref" -> AbstractExtensibleRef.setVerbatimRef(example, asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        example.addExtension(key, value);
                    }
                }
            }
        }
        return example;
    }

    private static Map<String, Encoding> toEncodingMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, Encoding> encodings = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Encoding encoding = toEncoding(entry.getValue());
            if (encoding != null) {
                encodings.put(String.valueOf(entry.getKey()), encoding);
            }
        }
        return encodings;
    }

    private static Encoding toEncoding(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Encoding encoding = OASFactory.createObject(Encoding.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "contentType" -> encoding.setContentType(asString(value));
                case HEADERS -> encoding.setHeaders(toHeaders(value));
                case STYLE -> encoding.setStyle(toEncodingStyle(asString(value)));
                case EXPLODE -> encoding.setExplode(asBoolean(value));
                case "allowReserved" -> encoding.setAllowReserved(asBoolean(value));
                default -> {
                    if (key.startsWith("x-")) {
                        encoding.addExtension(key, value);
                    }
                }
            }
        }
        return encoding;
    }

    private static Map<String, Link> toLinks(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, Link> links = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Link link = toLink(entry.getValue());
            if (link != null) {
                links.put(String.valueOf(entry.getKey()), link);
            }
        }
        return links;
    }

    private static Link toLink(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Link link = OASFactory.createObject(Link.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "$ref" -> AbstractExtensibleRef.setVerbatimRef(link, asString(value));
                case "operationRef" -> link.setOperationRef(asString(value));
                case "operationId" -> link.setOperationId(asString(value));
                case "requestBody" -> link.setRequestBody(asString(value));
                case DESCRIPTION -> link.setDescription(asString(value));
                case PARAMETERS -> addLinkParameters(link, value);
                case "server" -> link.setServer(toServer(value));
                default -> {
                    if (key.startsWith("x-")) {
                        link.addExtension(key, value);
                    }
                }
            }
        }
        return link;
    }

    private static void addLinkParameters(Link link, Object value) {
        if (value instanceof Map<?, ?> params) {
            for (Map.Entry<?, ?> p : params.entrySet()) {
                if (p.getKey() != null) {
                    link.addParameter(String.valueOf(p.getKey()), asString(p.getValue()));
                }
            }
        }
    }

    private static Map<String, SecurityScheme> toSecuritySchemes(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, SecurityScheme> schemes = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            SecurityScheme scheme = toSecurityScheme(entry.getValue());
            if (scheme != null) {
                schemes.put(String.valueOf(entry.getKey()), scheme);
            }
        }
        return schemes;
    }

    private static SecurityScheme toSecurityScheme(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        SecurityScheme scheme = OASFactory.createObject(SecurityScheme.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "type" -> scheme.setType(toSecuritySchemeType(asString(value)));
                case DESCRIPTION -> scheme.setDescription(asString(value));
                case "name" -> scheme.setName(asString(value));
                case "in" -> scheme.setIn(toSecuritySchemeIn(asString(value)));
                case "scheme" -> scheme.setScheme(asString(value));
                case "bearerFormat" -> scheme.setBearerFormat(asString(value));
                case "openIdConnectUrl" -> scheme.setOpenIdConnectUrl(asString(value));
                case "flows" -> scheme.setFlows(toOAuthFlows(value));
                case "$ref" -> AbstractExtensibleRef.setVerbatimRef(scheme, asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        scheme.addExtension(key, value);
                    }
                }
            }
        }
        return scheme;
    }

    private static OAuthFlows toOAuthFlows(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        OAuthFlows flows = OASFactory.createObject(OAuthFlows.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "implicit" -> flows.setImplicit(toOAuthFlow(value));
                case "password" -> flows.setPassword(toOAuthFlow(value));
                case "clientCredentials" -> flows.setClientCredentials(toOAuthFlow(value));
                case "authorizationCode" -> flows.setAuthorizationCode(toOAuthFlow(value));
                default -> {
                    if (key.startsWith("x-")) {
                        flows.addExtension(key, value);
                    }
                }
            }
        }
        return flows;
    }

    private static OAuthFlow toOAuthFlow(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        OAuthFlow flow = OASFactory.createObject(OAuthFlow.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "authorizationUrl" -> flow.setAuthorizationUrl(asString(value));
                case "tokenUrl" -> flow.setTokenUrl(asString(value));
                case "refreshUrl" -> flow.setRefreshUrl(asString(value));
                // A fresh flow has no scopes: a non-map value leaves it so.
                case "scopes" -> flow.setScopes(value instanceof Map<?, ?> scopes ? toScopes(scopes) : null);
                default -> {
                    if (key.startsWith("x-")) {
                        flow.addExtension(key, value);
                    }
                }
            }
        }
        return flow;
    }

    private static Map<String, String> toScopes(Map<?, ?> map) {
        LinkedHashMap<String, String> scopes = new LinkedHashMap<>();
        map.forEach((name, description) -> scopes.put(String.valueOf(name), asString(description)));
        return scopes;
    }

    private static SecurityScheme.Type toSecuritySchemeType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "apiKey" -> SecurityScheme.Type.APIKEY;
            case "http" -> SecurityScheme.Type.HTTP;
            case "oauth2" -> SecurityScheme.Type.OAUTH2;
            case "openIdConnect" -> SecurityScheme.Type.OPENIDCONNECT;
            case "mutualTLS" -> SecurityScheme.Type.MUTUALTLS;
            default -> null;
        };
    }

    private static SecurityScheme.In toSecuritySchemeIn(String in) {
        if (in == null) {
            return null;
        }
        return switch (in) {
            case "query" -> SecurityScheme.In.QUERY;
            case "header" -> SecurityScheme.In.HEADER;
            case "cookie" -> SecurityScheme.In.COOKIE;
            default -> null;
        };
    }

    private static Encoding.Style toEncodingStyle(String style) {
        if (style == null) {
            return null;
        }
        return switch (style) {
            case "form" -> Encoding.Style.FORM;
            case "spaceDelimited" -> Encoding.Style.SPACE_DELIMITED;
            case "pipeDelimited" -> Encoding.Style.PIPE_DELIMITED;
            case "deepObject" -> Encoding.Style.DEEP_OBJECT;
            default -> null;
        };
    }

    private static Map<String, Schema> toSchemas(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.LinkedHashMap<String, Schema> schemas = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Schema schema = toSchema(entry.getValue());
            if (schema != null) {
                schemas.put(String.valueOf(entry.getKey()), schema);
            }
        }
        return schemas;
    }

    /**
     * Maps a raw JSON/YAML schema onto the model. Since MP OpenAPI 4.2 (#698) a schema treats
     * every property it does not know as an extension, so each standard keyword is converted
     * here to the type of its model property (numbers to {@code BigDecimal}/{@code Integer},
     * objects to {@code Schema}/{@code Discriminator}/{@code XML}/{@code ExternalDocumentation}).
     * A keyword whose value has another JSON type is kept as written through
     * {@link Schema#set(String, Object)}, as an alternative dialect may use it.
     */
    static Schema toSchema(Object raw) {
        if (raw instanceof Boolean booleanSchema) {
            // JSON Schema 2020-12 §4.3.2: true / false are schemas wherever a schema is expected.
            Schema schema = OASFactory.createObject(Schema.class);
            schema.setBooleanSchema(booleanSchema);
            return schema;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Schema schema = OASFactory.createObject(Schema.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (!applyValidationKeyword(schema, key, value) && !applySubschemaKeyword(schema, key, value)) {
                applyOtherKeyword(schema, key, value);
            }
        }
        return schema;
    }

    /** Maps a JSON Schema validation keyword; {@code false} when {@code key} is not one. */
    private static boolean applyValidationKeyword(Schema schema, String key, Object value) {
        switch (key) {
            case "type" -> setTyped(schema, key, value, toSchemaTypes(value), schema::setType);
            case REQUIRED -> schema.setRequired(toStringList(value));
            case "enum" -> setEnumeration(schema, value);
            case "const" -> schema.setConstValue(value);
            case "multipleOf" -> setTyped(schema, key, value, asBigDecimal(value), schema::setMultipleOf);
            case "maximum" -> setTyped(schema, key, value, asBigDecimal(value), schema::setMaximum);
            case "exclusiveMaximum" -> setTyped(schema, key, value, asBigDecimal(value), schema::setExclusiveMaximum);
            case "minimum" -> setTyped(schema, key, value, asBigDecimal(value), schema::setMinimum);
            case "exclusiveMinimum" -> setTyped(schema, key, value, asBigDecimal(value), schema::setExclusiveMinimum);
            case "maxLength" -> setTyped(schema, key, value, asInteger(value), schema::setMaxLength);
            case "minLength" -> setTyped(schema, key, value, asInteger(value), schema::setMinLength);
            case "pattern" -> schema.setPattern(asString(value));
            case "maxItems" -> setTyped(schema, key, value, asInteger(value), schema::setMaxItems);
            case "minItems" -> setTyped(schema, key, value, asInteger(value), schema::setMinItems);
            case "uniqueItems" -> setTyped(schema, key, value,
                    value instanceof Boolean b ? b : null, schema::setUniqueItems);
            case "maxContains" -> setTyped(schema, key, value, asInteger(value), schema::setMaxContains);
            case "minContains" -> setTyped(schema, key, value, asInteger(value), schema::setMinContains);
            case "maxProperties" -> setTyped(schema, key, value, asInteger(value), schema::setMaxProperties);
            case "minProperties" -> setTyped(schema, key, value, asInteger(value), schema::setMinProperties);
            case "dependentRequired" -> setTyped(schema, key, value, toDependentRequired(value),
                    schema::setDependentRequired);
            case "nullable" -> setNullable(schema, value);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Maps a keyword whose value holds subschemas; {@code false} when {@code key} is not one. */
    private static boolean applySubschemaKeyword(Schema schema, String key, Object value) {
        switch (key) {
            case "properties" -> schema.setProperties(toSchemas(value));
            case "items" -> schema.setItems(toSchema(value));
            case "not" -> setTyped(schema, key, value, toSchema(value), schema::setNot);
            case "if" -> setTyped(schema, key, value, toSchema(value), schema::setIfSchema);
            case "then" -> setTyped(schema, key, value, toSchema(value), schema::setThenSchema);
            case "else" -> setTyped(schema, key, value, toSchema(value), schema::setElseSchema);
            case "contains" -> setTyped(schema, key, value, toSchema(value), schema::setContains);
            case "propertyNames" -> setTyped(schema, key, value, toSchema(value), schema::setPropertyNames);
            case "unevaluatedItems" -> setTyped(schema, key, value, toSchema(value), schema::setUnevaluatedItems);
            case "unevaluatedProperties" -> setTyped(schema, key, value, toSchema(value),
                    schema::setUnevaluatedProperties);
            case "contentSchema" -> setTyped(schema, key, value, toSchema(value), schema::setContentSchema);
            case "allOf" -> setTyped(schema, key, value, toSchemaList(value), schema::setAllOf);
            case "anyOf" -> setTyped(schema, key, value, toSchemaList(value), schema::setAnyOf);
            case "oneOf" -> setTyped(schema, key, value, toSchemaList(value), schema::setOneOf);
            case "prefixItems" -> setTyped(schema, key, value, toSchemaList(value), schema::setPrefixItems);
            case "dependentSchemas" -> setTyped(schema, key, value, toSchemas(value), schema::setDependentSchemas);
            case "patternProperties" -> setTyped(schema, key, value, toSchemas(value),
                    schema::setPatternProperties);
            case "additionalProperties" -> setAdditionalProperties(schema, value);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Maps the annotation, reference and OpenAPI keywords, then the extensions and unknown keywords. */
    private static void applyOtherKeyword(Schema schema, String key, Object value) {
        switch (key) {
            case DESCRIPTION -> schema.setDescription(asString(value));
            case "format" -> schema.setFormat(asString(value));
            case "discriminator" -> setTyped(schema, key, value, toDiscriminator(value), schema::setDiscriminator);
            case "xml" -> setTyped(schema, key, value, toXml(value), schema::setXml);
            case EXTERNAL_DOCS -> setTyped(schema, key, value, toExternalDocs(value), schema::setExternalDocs);
            case "contentEncoding" -> setTyped(schema, key, value,
                    value instanceof String s ? s : null, schema::setContentEncoding);
            case "contentMediaType" -> setTyped(schema, key, value,
                    value instanceof String s ? s : null, schema::setContentMediaType);
            case EXAMPLE -> schema.setExample(value);
            case "$ref" -> AbstractExtensibleRef.setVerbatimRef(schema, asString(value));
            case "$schema" -> schema.setSchemaDialect(asString(value));
            case "$comment" -> schema.setComment(asString(value));
            case "title" -> schema.setTitle(asString(value));
            case "default" -> schema.setDefaultValue(value);
            case DEPRECATED -> schema.setDeprecated(asBoolean(value));
            case "readOnly" -> schema.setReadOnly(asBoolean(value));
            case "writeOnly" -> schema.setWriteOnly(asBoolean(value));
            case EXAMPLES -> setSchemaExamples(schema, value);
            default -> {
                if (key.startsWith("x-")) {
                    schema.addExtension(key, value);
                } else {
                    // Preserve unknown / custom-dialect schema keywords.
                    schema.set(key, value);
                }
            }
        }
    }

    private static void setNullable(Schema schema, Object value) {
        if (Boolean.TRUE.equals(asBoolean(value))) {
            schema.addType(Schema.SchemaType.NULL);
        }
    }

    private static void setEnumeration(Schema schema, Object value) {
        if (value instanceof List<?> list) {
            schema.setEnumeration(new ArrayList<>(list));
        }
    }

    private static void setAdditionalProperties(Schema schema, Object value) {
        if (value instanceof Map<?, ?>) {
            schema.setAdditionalPropertiesSchema(toSchema(value));
        } else if (value instanceof Boolean b) {
            schema.setAdditionalPropertiesBoolean(b);
        }
    }

    private static void setSchemaExamples(Schema schema, Object value) {
        if (value instanceof List<?> list) {
            schema.setExamples(new ArrayList<>(list));
        }
    }

    /**
     * Sets {@code converted} through its typed setter, or keeps {@code raw} as written when it does
     * not have the JSON type of the standard keyword.
     */
    private static <T> void setTyped(Schema schema, String key, Object raw, T converted, Consumer<T> setter) {
        if (converted != null || raw == null) {
            setter.accept(converted);
        } else {
            schema.set(key, raw);
        }
    }

    private static BigDecimal asBigDecimal(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (!(raw instanceof Number number)) {
            return null;
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException _) {
            return null; // NaN or an infinity has no BigDecimal form
        }
    }

    private static Integer asInteger(Object raw) {
        BigDecimal decimal = asBigDecimal(raw);
        if (decimal == null) {
            return null;
        }
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException _) {
            return null;
        }
    }

    private static List<Schema> toSchemaList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        ArrayList<Schema> schemas = new ArrayList<>(list.size());
        for (Object item : list) {
            Schema schema = toSchema(item);
            if (schema != null) {
                schemas.add(schema);
            }
        }
        return schemas;
    }

    private static Map<String, List<String>> toDependentRequired(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        LinkedHashMap<String, List<String>> dependentRequired = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            List<String> names = toStringList(entry.getValue());
            if (names != null) {
                dependentRequired.put(String.valueOf(entry.getKey()), names);
            }
        }
        return dependentRequired;
    }

    private static Discriminator toDiscriminator(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        // Discriminator is not Extensible in MP OpenAPI 4.2: its x- keys go to a side store of the
        // implementation, which the serializer writes back.
        Discriminator discriminator = OASFactory.createObject(Discriminator.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "propertyName" -> discriminator.setPropertyName(asString(value));
                case "mapping" -> addDiscriminatorMappings(discriminator, value);
                default -> {
                    if (key.startsWith("x-") && discriminator instanceof DiscriminatorImpl impl) {
                        impl.putStaticExtension(key, value);
                    }
                }
            }
        }
        return discriminator;
    }

    private static void addDiscriminatorMappings(Discriminator discriminator, Object value) {
        if (value instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> m : mapping.entrySet()) {
                discriminator.addMapping(String.valueOf(m.getKey()), asString(m.getValue()));
            }
        }
    }

    private static XML toXml(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        XML xml = OASFactory.createObject(XML.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "name" -> xml.setName(asString(value));
                case "namespace" -> xml.setNamespace(asString(value));
                case "prefix" -> xml.setPrefix(asString(value));
                case "attribute" -> xml.setAttribute(asBoolean(value));
                case "wrapped" -> xml.setWrapped(asBoolean(value));
                default -> {
                    if (key.startsWith("x-")) {
                        xml.addExtension(key, value);
                    }
                }
            }
        }
        return xml;
    }

    /** The type names, or {@code null} when one of them is not a JSON Schema type (kept as written then). */
    private static List<Schema.SchemaType> toSchemaTypes(Object raw) {
        if (raw instanceof List<?> list) {
            ArrayList<Schema.SchemaType> types = new ArrayList<>(list.size());
            for (Object item : list) {
                Schema.SchemaType parsed = toSchemaType(item);
                if (parsed == null) {
                    return null;
                }
                types.add(parsed);
            }
            return types;
        }
        Schema.SchemaType parsed = toSchemaType(raw);
        return parsed == null ? null : List.of(parsed);
    }

    private static Schema.SchemaType toSchemaType(Object raw) {
        if (!(raw instanceof String name)) {
            return null;
        }
        return switch (name) {
            case "array" -> Schema.SchemaType.ARRAY;
            case "boolean" -> Schema.SchemaType.BOOLEAN;
            case "integer" -> Schema.SchemaType.INTEGER;
            case "null" -> Schema.SchemaType.NULL;
            case "number" -> Schema.SchemaType.NUMBER;
            case "object" -> Schema.SchemaType.OBJECT;
            case "string" -> Schema.SchemaType.STRING;
            default -> null;
        };
    }

    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        ArrayList<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            values.add(asString(item));
        }
        return values;
    }

    private static Boolean asBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw == null) {
            return null;
        }
        return Boolean.valueOf(String.valueOf(raw));
    }
}

