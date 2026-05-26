package io.vidocq.grimm.internal.factory;

import org.eclipse.microprofile.openapi.models.Constructible;
import org.eclipse.microprofile.openapi.spi.OASFactoryResolver;

public final class GrimmOASFactoryResolver extends OASFactoryResolver {

    @Override
    public <T extends Constructible> T createObject(Class<T> clazz) {
        return OASFactoryImpl.createObject(clazz);
    }
}

