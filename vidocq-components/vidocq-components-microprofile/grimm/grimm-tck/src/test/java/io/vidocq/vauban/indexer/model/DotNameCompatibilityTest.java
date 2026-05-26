package io.vidocq.vauban.indexer.model;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

class DotNameCompatibilityTest {

    @Test
    void fromDescriptor_supportsRegularClassDescriptor() {
        DotName dotName = DotName.fromDescriptor("Lcom/example/Foo;");
        assertEquals("com.example.Foo", dotName.value());
    }

    @Test
    void fromDescriptor_supportsArrayClassDescriptor() {
        DotName dotName = DotName.fromDescriptor("[Lorg/eclipse/microprofile/openapi/apps/petstore/model/Pet;");
        assertEquals("org.eclipse.microprofile.openapi.apps.petstore.model.Pet", dotName.value());
    }
}


