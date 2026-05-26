package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.info.Info;
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
}

