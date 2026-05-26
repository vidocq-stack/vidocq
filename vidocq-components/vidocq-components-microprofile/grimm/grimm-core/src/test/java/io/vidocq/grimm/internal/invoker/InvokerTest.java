package io.vidocq.grimm.internal.invoker;

import io.vidocq.grimm.internal.config.FilterConfig;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilterInvoker} and {@link ModelReaderInvoker}.
 *
 * Spec §4.1 / §4.3: Filter and ModelReader invocation.
 */
class InvokerTest {

    @Test
    void filterInvoker_noFilterConfigured_returnsModel() {
        // When no filter is configured, model should be returned unchanged
        FilterConfig config = FilterConfig.defaultConfig();
        OpenAPI model = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Test API", "1.0"));

        FilterInvoker invoker = new FilterInvoker();
        OpenAPI result = invoker.applyFilter(model, config);

        assertSame(model, result);
    }

    @Test
    void modelReaderInvoker_noReaderConfigured_returnsNull() {
        // When no reader is configured, should return null
        FilterConfig config = FilterConfig.defaultConfig();

        ModelReaderInvoker invoker = new ModelReaderInvoker();
        OpenAPI result = invoker.invokeModelReader(config);

        assertNull(result);
    }

    @Test
    void modelReaderInvoker_readerConfigured_invokesReader() {
        // Spec §4.1: OASModelReader is instantiated and called
        FilterConfig config = new FilterConfig(null, "io.vidocq.grimm.internal.invoker.InvokerTest$FakeModelReader");

        ModelReaderInvoker invoker = new ModelReaderInvoker();
        OpenAPI result = invoker.invokeModelReader(config);

        assertNotNull(result);
        assertEquals("From Reader", result.getInfo().getTitle());
    }

    @Test
    void filterInvoker_filterConfigured_appliesFilter() {
        // Spec §4.3: OASFilter is instantiated and applied
        FilterConfig config = new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$FakeFilter", null);
        OpenAPI model = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Test API", "1.0"));

        FilterInvoker invoker = new FilterInvoker();
        OpenAPI result = invoker.applyFilter(model, config);

        assertNotNull(result);
        // Filter modifies the title
        assertEquals("Filtered: Test API", result.getInfo().getTitle());
    }

    private Info createInfo(String title, String version) {
        return OASFactory.createObject(Info.class)
            .title(title)
            .version(version);
    }

    // Fake implementations for testing

    public static class FakeModelReader implements OASModelReader {
        @Override
        public OpenAPI buildModel() {
            return OASFactory.createObject(OpenAPI.class)
                .info(OASFactory.createObject(Info.class)
                    .title("From Reader")
                    .version("2.0"));
        }
    }

    public static class FakeFilter implements OASFilter {
        @Override
        public void filterOpenAPI(OpenAPI openAPI) {
            if (openAPI.getInfo() != null) {
                openAPI.getInfo().setTitle("Filtered: " + openAPI.getInfo().getTitle());
            }
        }
    }
}



