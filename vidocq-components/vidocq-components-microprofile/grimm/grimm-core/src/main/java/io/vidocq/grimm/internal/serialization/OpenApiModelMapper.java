package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
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
                default -> {
                    if (key.startsWith("x-")) {
                        info.addExtension(key, value);
                    }
                }
            }
        }
        return info;
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
                default -> {
                    if (key.startsWith("x-")) {
                        schema.addExtension(key, value);
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

