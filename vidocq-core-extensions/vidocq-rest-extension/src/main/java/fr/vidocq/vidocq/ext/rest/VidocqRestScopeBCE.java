package fr.vidocq.vidocq.ext.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

/**
 * Build Compatible Extension qui ajoute automatiquement {@code @RequestScoped}
 * aux classes annotées {@code @Path} si aucun scope CDI n'est présent.
 * <p>
 * Comportement identique à MicroProfile REST Client / SmallRye JAX-RS.
 * </p>
 */
public class VidocqRestScopeBCE implements BuildCompatibleExtension {

    private static final System.Logger LOG = System.getLogger(VidocqRestScopeBCE.class.getName());

    @SuppressWarnings("unused")
    @Enhancement(types = Object.class, withAnnotations = Path.class)
    public void addDefaultScope(ClassConfig clazz) {
        var info = clazz.info();

        boolean hasScope = info.hasAnnotation(RequestScoped.class)
                || info.hasAnnotation(ApplicationScoped.class)
                || info.hasAnnotation(SessionScoped.class)
                || info.hasAnnotation(Dependent.class)
                || info.hasAnnotation(Singleton.class);

        if (!hasScope) {
            clazz.addAnnotation(RequestScoped.class);
            LOG.log(System.Logger.Level.INFO,
                    "  @Path class {0} has no CDI scope, defaulting to @RequestScoped",
                    info.name());
        }
    }
}
