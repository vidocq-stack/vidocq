package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

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
    void merge_annotationTagsOverrideStaticTags() {
        // Tag lists are replaced entirely by the highest-priority non-empty source.
        OpenAPI staticModel = OASFactory.createObject(OpenAPI.class)
                .tags(List.of(OASFactory.createObject(Tag.class).name("legacy")));
        OpenAPI annotationModel = OASFactory.createObject(OpenAPI.class)
                .tags(List.of(OASFactory.createObject(Tag.class).name("modern")));

        OpenAPI result = new ModelMerger().merge(List.of(
                new StaticFileSource(staticModel),
                new AnnotationSource(annotationModel)));

        assertEquals(1, result.getTags().size());
        assertEquals("modern", result.getTags().get(0).getName());
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

    private OpenAPI withPath(String path, String opId) {
        OpenAPI api = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem item = OASFactory.createObject(PathItem.class);
        item.setGET(OASFactory.createObject(Operation.class).operationId(opId));
        paths.addPathItem(path, item);
        api.setPaths(paths);
        return api;
    }
}

