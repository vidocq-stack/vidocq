package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

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
                case "paths" -> openAPI.setPaths(toPaths(value));
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
                case "responses" -> operation.setResponses(toApiResponses(value));
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
            } else if (key.startsWith("x-")) {
                response.addExtension(key, value);
            }
        }
        return response;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

