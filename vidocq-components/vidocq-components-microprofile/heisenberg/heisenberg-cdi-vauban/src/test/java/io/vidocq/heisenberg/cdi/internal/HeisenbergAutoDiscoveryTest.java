package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HeisenbergAutoDiscoveryTest {

    @Test
    void registersConfigProviderResolverInServiceDescriptor() throws Exception {
        String resourcePath = "META-INF/services/org.eclipse.microprofile.config.spi.ConfigProviderResolver";
        try (var input = HeisenbergAutoDiscovery.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(input);
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains(HeisenbergAutoDiscovery.class.getName()));
        }
    }
}
