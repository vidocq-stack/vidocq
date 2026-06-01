package io.vidocq.cervantes.jaxrs;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Applique les annotations d'autorisation JSR-250 sur les méthodes de ressources JAX-RS
 * (MicroProfile JWT 2.1 §1.1). Pour chaque méthode, enregistre un {@link RolesAllowedRequestFilter}
 * selon l'annotation effective.
 *
 * <p>Précédence : les annotations au niveau <em>méthode</em> l'emportent sur celles au niveau
 * <em>classe</em>. Sur une cible donnée, ordre {@code @DenyAll} &gt; {@code @RolesAllowed} &gt;
 * {@code @PermitAll}. Une méthode sans annotation effective reste ouverte (aucun filtre).</p>
 */
@Provider
@ApplicationScoped
public class RolesAllowedDynamicFeature implements DynamicFeature {

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        Method method = resourceInfo.getResourceMethod();

        // Niveau méthode (prioritaire).
        if (method.isAnnotationPresent(DenyAll.class)) {
            context.register(RolesAllowedRequestFilter.denyAll());
            return;
        }
        RolesAllowed methodRoles = method.getAnnotation(RolesAllowed.class);
        if (methodRoles != null) {
            context.register(RolesAllowedRequestFilter.rolesAllowed(Set.of(methodRoles.value())));
            return;
        }
        if (method.isAnnotationPresent(PermitAll.class)) {
            return; // @PermitAll explicite : aucune contrainte
        }

        // Repli niveau classe.
        Class<?> resourceClass = resourceInfo.getResourceClass();
        if (resourceClass.isAnnotationPresent(DenyAll.class)) {
            context.register(RolesAllowedRequestFilter.denyAll());
            return;
        }
        RolesAllowed classRoles = resourceClass.getAnnotation(RolesAllowed.class);
        if (classRoles != null) {
            context.register(RolesAllowedRequestFilter.rolesAllowed(Set.of(classRoles.value())));
        }
        // Sinon : aucune annotation de sécurité → endpoint ouvert.
    }
}
