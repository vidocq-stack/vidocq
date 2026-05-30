package io.vidocq.cervantes.cdi;

import io.vidocq.cervantes.cdi.internal.ClaimResolver;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * {@link SyntheticBeanCreator} which solves at runtime the value of an injection point
 * {@code @Claim} delegating to {@link ClaimResolver}.
 *
 * <p>Classe {@code public} top level manufacturer without argument (contract CDI 4.1)
 * §SyntheticBeanCreater); is not herself a bean. The current {@link InjectionPoint} and the
 * {@link JsonWebTokenContext} query are obtained via the parameter ZZPH1ZZ.ZZPH2ZZ
 */
public class ClaimSyntheticCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        InjectionPoint injectionPoint = lookup.select(InjectionPoint.class).get();
        JsonWebTokenContext context = lookup.select(JsonWebTokenContext.class).get();
        return ClaimResolver.resolve(injectionPoint, context);
    }
}
