package io.vidocq.grimm.internal.serialization;

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
import org.eclipse.microprofile.openapi.models.media.Encoding;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.tags.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class OpenApiModelMapper {

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
                case "externalDocs" -> openAPI.setExternalDocs(toExternalDocs(value));
                case "servers" -> openAPI.setServers(toServers(value));
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
                case "summary" -> info.setSummary(asString(value));
                case "description" -> info.setDescription(asString(value));
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
                case "description" -> externalDocs.setDescription(asString(value));
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
                case "description" -> server.setDescription(asString(value));
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
                case "description" -> tag.setDescription(asString(value));
                case "externalDocs" -> tag.setExternalDocs(toExternalDocs(value));
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
                case "summary" -> pathItem.setSummary(asString(value));
                case "description" -> pathItem.setDescription(asString(value));
                case "$ref" -> pathItem.setRef(asString(value));
                case "servers" -> pathItem.setServers(toServers(value));
                case "parameters" -> pathItem.setParameters(toParameters(value));
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
                case "summary" -> operation.setSummary(asString(value));
                case "description" -> operation.setDescription(asString(value));
                case "parameters" -> operation.setParameters(toParameters(value));
                case "requestBody" -> operation.setRequestBody(toRequestBody(value));
                case "responses" -> operation.setResponses(toApiResponses(value));
                case "callbacks" -> operation.setCallbacks(toCallbacks(value));
                case "tags" -> operation.setTags(toStringList(value));
                case "deprecated" -> operation.setDeprecated(asBoolean(value));
                case "servers" -> operation.setServers(toServers(value));
                case "externalDocs" -> operation.setExternalDocs(toExternalDocs(value));
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
            if ("description".equals(key)) {
                response.setDescription(asString(value));
            } else if ("content".equals(key)) {
                response.setContent(toContent(value));
            } else if ("headers".equals(key)) {
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
                case "headers" -> components.setHeaders(toHeaders(value));
                case "schemas" -> components.setSchemas(toSchemas(value));
                case "parameters" -> components.setParameters(toParametersMap(value));
                case "requestBodies" -> components.setRequestBodies(toRequestBodies(value));
                case "responses" -> components.setResponses(toResponsesMap(value));
                case "examples" -> components.setExamples(toExamples(value));
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
                case "description" -> variable.setDescription(asString(value));
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
                case "required" -> parameter.setRequired(asBoolean(value));
                case "description" -> parameter.setDescription(asString(value));
                case "schema" -> parameter.setSchema(toSchema(value));
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

    private static RequestBody toRequestBody(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        RequestBody requestBody = OASFactory.createObject(RequestBody.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "description" -> requestBody.setDescription(asString(value));
                case "required" -> requestBody.setRequired(asBoolean(value));
                case "content" -> requestBody.setContent(toContent(value));
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
            if (expression.startsWith("x-")) {
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
                case "schema" -> mediaType.setSchema(toSchema(value));
                case "example" -> mediaType.setExample(value);
                case "examples" -> mediaType.setExamples(toExamples(value));
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

    private static Header toHeader(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Header header = OASFactory.createObject(Header.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "description" -> header.setDescription(asString(value));
                case "required" -> header.setRequired(asBoolean(value));
                case "deprecated" -> header.setDeprecated(asBoolean(value));
                case "allowEmptyValue" -> header.setAllowEmptyValue(asBoolean(value));
                case "schema" -> header.setSchema(toSchema(value));
                case "$ref" -> header.setRef(asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        header.addExtension(key, value);
                    }
                }
            }
        }
        return header;
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
                case "summary" -> example.setSummary(asString(value));
                case "description" -> example.setDescription(asString(value));
                case "value" -> example.setValue(value);
                case "externalValue" -> example.setExternalValue(asString(value));
                case "$ref" -> example.setRef(asString(value));
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
                case "headers" -> encoding.setHeaders(toHeaders(value));
                case "style" -> encoding.setStyle(toEncodingStyle(asString(value)));
                case "explode" -> encoding.setExplode(asBoolean(value));
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
                case "$ref" -> link.setRef(asString(value));
                case "operationRef" -> link.setOperationRef(asString(value));
                case "operationId" -> link.setOperationId(asString(value));
                case "requestBody" -> link.setRequestBody(asString(value));
                case "description" -> link.setDescription(asString(value));
                case "parameters" -> {
                    if (value instanceof Map<?, ?> params) {
                        for (Map.Entry<?, ?> p : params.entrySet()) {
                            if (p.getKey() != null) {
                                link.addParameter(String.valueOf(p.getKey()), asString(p.getValue()));
                            }
                        }
                    }
                }
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
                case "description" -> scheme.setDescription(asString(value));
                case "name" -> scheme.setName(asString(value));
                case "in" -> scheme.setIn(toSecuritySchemeIn(asString(value)));
                case "scheme" -> scheme.setScheme(asString(value));
                case "bearerFormat" -> scheme.setBearerFormat(asString(value));
                case "openIdConnectUrl" -> scheme.setOpenIdConnectUrl(asString(value));
                case "$ref" -> scheme.setRef(asString(value));
                default -> {
                    if (key.startsWith("x-")) {
                        scheme.addExtension(key, value);
                    }
                }
            }
        }
        return scheme;
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

    private static Schema toSchema(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Schema schema = OASFactory.createObject(Schema.class);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "description" -> schema.setDescription(asString(value));
                case "format" -> schema.setFormat(asString(value));
                case "type" -> schema.setType(toSchemaTypes(value));
                case "required" -> schema.setRequired(toStringList(value));
                case "properties" -> schema.setProperties(toSchemas(value));
                case "items" -> schema.setItems(toSchema(value));
                case "$ref" -> schema.setRef(asString(value));
                case "$schema" -> schema.setSchemaDialect(asString(value));
                case "$comment" -> schema.setComment(asString(value));
                case "title" -> schema.setTitle(asString(value));
                case "default" -> schema.setDefaultValue(value);
                case "pattern" -> schema.setPattern(asString(value));
                case "deprecated" -> schema.setDeprecated(asBoolean(value));
                case "readOnly" -> schema.setReadOnly(asBoolean(value));
                case "writeOnly" -> schema.setWriteOnly(asBoolean(value));
                case "nullable" -> {
                    if (Boolean.TRUE.equals(asBoolean(value))) {
                        schema.addType(Schema.SchemaType.NULL);
                    }
                }
                case "enum" -> {
                    if (value instanceof List<?> list) {
                        schema.setEnumeration(new ArrayList<>(list));
                    }
                }
                case "additionalProperties" -> {
                    if (value instanceof Map<?, ?>) {
                        schema.setAdditionalPropertiesSchema(toSchema(value));
                    } else if (value instanceof Boolean b) {
                        schema.setAdditionalPropertiesBoolean(b);
                    }
                }
                case "examples" -> {
                    if (value instanceof List<?> list) {
                        schema.setExamples(new ArrayList<>(list));
                    }
                }
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
        return schema;
    }

    private static List<Schema.SchemaType> toSchemaTypes(Object raw) {
        if (raw instanceof List<?> list) {
            ArrayList<Schema.SchemaType> types = new ArrayList<>(list.size());
            for (Object item : list) {
                Schema.SchemaType parsed = toSchemaType(asString(item));
                if (parsed != null) {
                    types.add(parsed);
                }
            }
            return types;
        }
        Schema.SchemaType parsed = toSchemaType(asString(raw));
        return parsed == null ? null : List.of(parsed);
    }

    private static Schema.SchemaType toSchemaType(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "array" -> Schema.SchemaType.ARRAY;
            case "boolean" -> Schema.SchemaType.BOOLEAN;
            case "integer" -> Schema.SchemaType.INTEGER;
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

