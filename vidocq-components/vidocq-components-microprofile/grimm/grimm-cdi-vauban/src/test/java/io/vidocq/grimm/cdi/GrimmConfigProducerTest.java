package io.vidocq.grimm.cdi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrimmConfigProducerTest {

    @AfterEach
    void cleanup() {
        GrimmExtension.resetForTesting();
    }

    @Test
    void produceGrimmConfig_neverReturnsNull() {
        GrimmConfigProducer producer = new GrimmConfigProducer();
        assertNotNull(producer.produceGrimmConfig());
    }

    @Test
    void produceScannedTypes_usesExtensionDiscovery() {
        GrimmExtension.recordForTesting(GrimmConfigProducerTest.class.getName());

        GrimmConfigProducer producer = new GrimmConfigProducer();
        ScannedTypes scanned = producer.produceScannedTypes();

        assertTrue(scanned.classes().contains(GrimmConfigProducerTest.class));
    }
}

