package io.vidocq.cervantes.cdi;

import io.vidocq.cervantes.cdi.internal.ClaimResolver;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * {@link SyntheticBeanCreator} qui résout, au runtime, la valeur d'un point d'injection
 * {@code @Claim} en déléguant à {@link ClaimResolver}.
 *
 * <p>Classe {@code public} top-level à constructeur sans argument (contrat CDI 4.1
 * §SyntheticBeanCreator) ; n'est pas elle-même un bean. L'{@link InjectionPoint} courant et le
 * {@link JsonWebTokenContext} de requête sont obtenus via le paramètre {@link Instance}.</p>
 */
public class ClaimSyntheticCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        InjectionPoint injectionPoint = lookup.select(InjectionPoint.class).get();
        JsonWebTokenContext context = lookup.select(JsonWebTokenContext.class).get();
        return ClaimResolver.resolve(injectionPoint, context);
    }
}
