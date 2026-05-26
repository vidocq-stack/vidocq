package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ModelMerger}.
 *
 * Spec §4.4: Priority order is annotations > OASModelReader > static file.
 */
class ModelMergerTest {

    @Test
    void merge_prioritizesAnnotationOverReader() {
        // Spec §4.4: Annotation has highest priority
        OpenAPI annotationModel = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Annotation API", "1.0.0"));

        OpenAPI readerModel = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Reader API", "2.0.0"));

        ModelMerger merger = new ModelMerger();
        OpenAPI result = merger.merge(List.of(
            new AnnotationSource(annotationModel),
            new ReaderSource(readerModel)
        ));

        assertEquals("Annotation API", result.getInfo().getTitle(),
            "Annotation model should take priority");
    }

    @Test
    void merge_prioritizesReaderOverStaticFile() {
        // Spec §4.4: Reader has higher priority than static file
        OpenAPI staticModel = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Static API", "1.0.0"));

        OpenAPI readerModel = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Reader API", "2.0.0"));

        ModelMerger merger = new ModelMerger();
        OpenAPI result = merger.merge(List.of(
            new StaticFileSource(staticModel),
            new ReaderSource(readerModel)
        ));

        assertEquals("Reader API", result.getInfo().getTitle(),
            "Reader model should take priority over static file");
    }

    @Test
    void merge_allThreeSources() {
        // Spec §4.4: Complete priority chain
        OpenAPI staticModel = OASFactory.createObject(OpenAPI.class)
            .openapi("3.1.0")
            .info(createInfo("Static API", "1.0.0"));

        OpenAPI readerModel = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Reader API", "2.0.0"));

        OpenAPI annotationModel = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Annotation API", "3.0.0"));

        ModelMerger merger = new ModelMerger();
        OpenAPI result = merger.merge(List.of(
            new StaticFileSource(staticModel),
            new ReaderSource(readerModel),
            new AnnotationSource(annotationModel)
        ));

        // Annotation has highest priority for info
        assertEquals("Annotation API", result.getInfo().getTitle());
        // Static file sets openapi version, not overridden
        assertEquals("3.1.0", result.getOpenapi());
    }

    @Test
    void merge_nullModelsAreSkipped() {
        // Null models should not cause errors
        ModelMerger merger = new ModelMerger();
        OpenAPI result = merger.merge(List.of(
            new AnnotationSource(null),
            new ReaderSource(null),
            new StaticFileSource(null)
        ));

        assertNotNull(result);
    }

    private Info createInfo(String title, String version) {
        return OASFactory.createObject(Info.class)
            .title(title)
            .version(version);
    }

    // ---------------- Additional coverage (spec §4.4) ----------------

    @Test
    void merge_emptySourceList_returnsEmptyModel() {
        // Absent sources → non-null but skeleton model.
        OpenAPI result = new ModelMerger().merge(List.of());
        assertNotNull(result);
        assertNull(result.getInfo());
        assertNull(result.getPaths());
    }

    @Test
    void merge_onlyStaticFile_returnedAsIs() {
        OpenAPI staticModel = OASFactory.createObject(OpenAPI.class)
                .openapi("3.1.0")
                .info(createInfo("Static API", "1.0"));
        OpenAPI result = new ModelMerger().merge(List.of(new StaticFileSource(staticModel)));
        assertEquals("3.1.0", result.getOpenapi());
        assertEquals("Static API", result.getInfo().getTitle());
    }

    @Test
    void merge_annotationPathOverridesStaticPath() {
        // Same path key present in both → annotation wins (highest priority).
        OpenAPI staticModel = withPath("/pets", "listPetsStatic");
        OpenAPI annotationModel = withPath("/pets", "listPetsAnnotation");

        OpenAPI result = new ModelMerger().merge(List.of(
                new StaticFileSource(staticModel),
                new AnnotationSource(annotationModel)));

        assertEquals("listPetsAnnotation",
                result.getPaths().getPathItem("/pets").getGET().getOperationId());
    }

    @Test
    void merge_pathsAreUnionedAcrossSources() {
        // Different keys → union, with each source contributing its own path.
        OpenAPI staticModel = withPath("/a", "fromStatic");
        OpenAPI readerModel = withPath("/b", "fromReader");
        OpenAPI annotationModel = withPath("/c", "fromAnnotation");

        OpenAPI result = new ModelMerger().merge(List.of(
                new StaticFileSource(staticModel),
                new ReaderSource(readerModel),
                new AnnotationSource(annotationModel)));

        assertEquals("fromStatic", result.getPaths().getPathItem("/a").getGET().getOperationId());
        assertEquals("fromReader", result.getPaths().getPathItem("/b").getGET().getOperationId());
        assertEquals("fromAnnotation", result.getPaths().getPathItem("/c").getGET().getOperationId());
    }

    @Test
    void merge_annotationTagsAreMergedWithStaticTags() {
        // Tags are merged by name to avoid dropping lower-priority declarations.
        OpenAPI staticModel = OASFactory.createObject(OpenAPI.class)
                .tags(List.of(OASFactory.createObject(Tag.class).name("legacy")));
        OpenAPI annotationModel = OASFactory.createObject(OpenAPI.class)
                .tags(List.of(OASFactory.createObject(Tag.class).name("modern")));

        OpenAPI result = new ModelMerger().merge(List.of(
                new StaticFileSource(staticModel),
                new AnnotationSource(annotationModel)));

        assertEquals(2, result.getTags().size());
        assertTrue(result.getTags().stream().anyMatch(t -> "legacy".equals(t.getName())));
        assertTrue(result.getTags().stream().anyMatch(t -> "modern".equals(t.getName())));
    }

    @Test
    void merge_readerOverridesStaticInfo() {
        OpenAPI staticModel = OASFactory.createObject(OpenAPI.class)
                .info(createInfo("Static", "1.0"));
        OpenAPI readerModel = OASFactory.createObject(OpenAPI.class)
                .info(createInfo("Reader", "2.0"));

        OpenAPI result = new ModelMerger().merge(List.of(
                new StaticFileSource(staticModel),
                new ReaderSource(readerModel)));

        assertEquals("Reader", result.getInfo().getTitle());
        assertEquals("2.0", result.getInfo().getVersion());
    }

    @Test
    void merge_componentsMapsAreUnionedAcrossSources() {
        Components staticComponents = OASFactory.createObject(Components.class)
                .schemas(Map.of("StaticSchema", OASFactory.createObject(Schema.class).type(List.of(Schema.SchemaType.OBJECT))));
        Components readerComponents = OASFactory.createObject(Components.class)
                .schemas(Map.of("ReaderSchema", OASFactory.createObject(Schema.class).type(List.of(Schema.SchemaType.OBJECT))));
        Components annotationComponents = OASFactory.createObject(Components.class)
                .schemas(Map.of("AnnotationSchema", OASFactory.createObject(Schema.class).type(List.of(Schema.SchemaType.OBJECT))));

        OpenAPI result = new ModelMerger().merge(List.of(
                new StaticFileSource(OASFactory.createObject(OpenAPI.class).components(staticComponents)),
                new ReaderSource(OASFactory.createObject(OpenAPI.class).components(readerComponents)),
                new AnnotationSource(OASFactory.createObject(OpenAPI.class).components(annotationComponents))));

        assertNotNull(result.getComponents());
        assertNotNull(result.getComponents().getSchemas().get("StaticSchema"));
        assertNotNull(result.getComponents().getSchemas().get("ReaderSchema"));
        assertNotNull(result.getComponents().getSchemas().get("AnnotationSchema"));
    }

    @Test
    void merge_samePathCombinesOperationTags() {
        OpenAPI readerModel = withPathAndTags("/availability", "reader-op", "Availability");
        OpenAPI annotationModel = withPathAndTags("/availability", "annotation-op", "Get Flights");

        OpenAPI result = new ModelMerger().merge(List.of(
                new ReaderSource(readerModel),
                new AnnotationSource(annotationModel)));

        Operation get = result.getPaths().getPathItem("/availability").getGET();
        assertEquals("annotation-op", get.getOperationId());
        assertEquals(2, get.getTags().size());
        assertTrue(get.getTags().contains("Availability"));
        assertTrue(get.getTags().contains("Get Flights"));
    }

    @Test
    void merge_preservesReaderTopLevelMetadataNotHandledByScanner() {
        OpenAPI readerModel = OASFactory.createObject(OpenAPI.class);
        readerModel.setExternalDocs(OASFactory.createObject(ExternalDocumentation.class)
                .description("reader-doc")
                .url("https://example.com/reader"));
        readerModel.setJsonSchemaDialect("https://json-schema.org/draft/2020-12/schema");
        readerModel.setWebhooks(Map.of("bookingEvent", OASFactory.createObject(PathItem.class)
                .PUT(OASFactory.createObject(Operation.class).operationId("bookingWebhook"))));

        OpenAPI result = new ModelMerger().merge(List.of(
                new ReaderSource(readerModel),
                new AnnotationSource(OASFactory.createObject(OpenAPI.class))));

        assertEquals("reader-doc", result.getExternalDocs().getDescription());
        assertEquals("https://json-schema.org/draft/2020-12/schema", result.getJsonSchemaDialect());
        assertNotNull(result.getWebhooks().get("bookingEvent").getPUT());
    }

    @Test
    void merge_componentsCallbacksPreserveReaderRefWhenAnnotationHasSameKey() {
        var readerCallback = OASFactory.createObject(org.eclipse.microprofile.openapi.models.callbacks.Callback.class)
                .ref("#/components/callbacks/availabilityCallback");
        OpenAPI readerModel = OASFactory.createObject(OpenAPI.class)
                .components(OASFactory.createObject(Components.class)
                        .callbacks(Map.of("availabilityCallbackRef", readerCallback)));

        var annotationCallback = OASFactory.createObject(org.eclipse.microprofile.openapi.models.callbacks.Callback.class);
        OpenAPI annotationModel = OASFactory.createObject(OpenAPI.class)
                .components(OASFactory.createObject(Components.class)
                        .callbacks(Map.of("availabilityCallbackRef", annotationCallback)));

        OpenAPI result = new ModelMerger().merge(List.of(
                new ReaderSource(readerModel),
                new AnnotationSource(annotationModel)));

        assertEquals("#/components/callbacks/availabilityCallback",
                result.getComponents().getCallbacks().get("availabilityCallbackRef").getRef());
    }

    @Test
    void merge_mediaTypeSchemaKeepsItemsWhenAnnotationAddsRef() {
        Schema readerSchema = OASFactory.createObject(Schema.class)
                .type(List.of(Schema.SchemaType.ARRAY))
                .items(OASFactory.createObject(Schema.class)
                        .ref("#/components/schemas/Availability"));
        MediaType readerMediaType = OASFactory.createObject(MediaType.class).schema(readerSchema);
        Content readerContent = OASFactory.createObject(Content.class).addMediaType("application/json", readerMediaType);
        APIResponse readerResponse = OASFactory.createObject(APIResponse.class).content(readerContent);
        APIResponses readerResponses = OASFactory.createObject(APIResponses.class).addAPIResponse("200", readerResponse);

        Schema annotationSchema = OASFactory.createObject(Schema.class).ref("#/components/schemas/Flight");
        MediaType annotationMediaType = OASFactory.createObject(MediaType.class).schema(annotationSchema);
        Content annotationContent = OASFactory.createObject(Content.class).addMediaType("application/json", annotationMediaType);
        APIResponse annotationResponse = OASFactory.createObject(APIResponse.class).content(annotationContent);
        APIResponses annotationResponses = OASFactory.createObject(APIResponses.class).addAPIResponse("200", annotationResponse);

        OpenAPI result = new ModelMerger().merge(List.of(
                new ReaderSource(withResponses("/availability", readerResponses)),
                new AnnotationSource(withResponses("/availability", annotationResponses))));

        Schema merged = result.getPaths().getPathItem("/availability").getGET()
                .getResponses().getAPIResponse("200")
                .getContent().getMediaType("application/json").getSchema();
        assertEquals("#/components/schemas/Flight", merged.getRef());
        assertNotNull(merged.getItems());
        assertEquals("#/components/schemas/Availability", merged.getItems().getRef());
    }

    private OpenAPI withPath(String path, String opId) {
        OpenAPI api = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem item = OASFactory.createObject(PathItem.class);
        item.setGET(OASFactory.createObject(Operation.class).operationId(opId));
        paths.addPathItem(path, item);
        api.setPaths(paths);
        return api;
    }

    private OpenAPI withPathAndTags(String path, String opId, String tag) {
        OpenAPI api = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem item = OASFactory.createObject(PathItem.class);
        item.setGET(OASFactory.createObject(Operation.class)
                .operationId(opId)
                .tags(List.of(tag)));
        paths.addPathItem(path, item);
        api.setPaths(paths);
        return api;
    }

    private OpenAPI withResponses(String path, APIResponses responses) {
        OpenAPI api = OASFactory.createObject(OpenAPI.class);
        PathItem item = OASFactory.createObject(PathItem.class)
                .GET(OASFactory.createObject(Operation.class).responses(responses));
        api.setPaths(OASFactory.createObject(Paths.class).addPathItem(path, item));
        return api;
    }
}

