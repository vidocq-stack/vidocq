package io.vidocq.grimm.internal.invoker;

import io.vidocq.grimm.internal.config.FilterConfig;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilterInvokerTest {

    @Test
    void applyFilter_filtersSchemaBeforeApiResponse() {
        // Spec §4.3 (leaf -> parent): filterSchema must run before filterAPIResponse.
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem pathItem = OASFactory.createObject(PathItem.class);
        Operation post = OASFactory.createObject(Operation.class);

        APIResponses responses = OASFactory.createObject(APIResponses.class);
        APIResponse response = OASFactory.createObject(APIResponse.class);
        response.setDescription("created");

        Content content = OASFactory.createObject(Content.class);
        MediaType mediaType = OASFactory.createObject(MediaType.class);
        Schema schema = OASFactory.createObject(Schema.class);
        schema.setDescription("id of the new review");
        mediaType.setSchema(schema);
        content.addMediaType("application/json", mediaType);
        response.setContent(content);
        responses.addAPIResponse("201", response);

        post.setResponses(responses);
        pathItem.setPOST(post);
        paths.addPathItem("/reviews", pathItem);
        model.setPaths(paths);

        FilterInvoker invoker = new FilterInvoker();
        invoker.applyFilter(model, new FilterConfig(ParentOverChildFilter.class.getName(), null));

        String description = model.getPaths().getPathItem("/reviews").getPOST()
                .getResponses().getAPIResponse("201")
                .getContent().getMediaType("application/json")
                .getSchema().getDescription();
        assertEquals("parent - id of the new review", description);
    }

    public static final class ParentOverChildFilter implements OASFilter {
        @Override
        public Schema filterSchema(Schema schema) {
            if ("id of the new review".equals(schema.getDescription())) {
                schema.setDescription("child - id of the new review");
            }
            return schema;
        }

        @Override
        public APIResponse filterAPIResponse(APIResponse response) {
            if (response.getContent() == null || !response.getContent().hasMediaType("application/json")) {
                return response;
            }
            Schema schema = response.getContent().getMediaType("application/json").getSchema();
            if (schema != null && "child - id of the new review".equals(schema.getDescription())) {
                schema.setDescription("parent - id of the new review");
            }
            return response;
        }
    }
}

