package io.vidocq.humboldt.cdi;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Extension CDI 4.x compatible Lite et Full — détecte au build time les classes
 * et méthodes portant {@link io.opentelemetry.instrumentation.annotations.WithSpan @WithSpan}
 * et leur ajoute automatiquement le marker {@link SpanBinding}, ce qui déclenche
 * l'activation de {@link WithSpanInterceptor} par le container CDI.
 *
 * <p>Résultat : l'utilisateur écrit uniquement {@code @WithSpan} (annotation
 * API publique OTel standardisée et attendue par le TCK MicroProfile Telemetry
 * 2.1). Pas de double annotation.</p>
 *
 * <p>Découverte : via ServiceLoader CDI (entrée dans
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * et binding {@code provides} JPMS). Doit être appelée automatiquement par
 * tout container CDI 4.x conforme (Vauban CDI Lite, Weld 5+, etc.).</p>
 */
public final class HumboldtBuildCompatibleExtension implements BuildCompatibleExtension {

    private static final Logger LOG = System.getLogger(HumboldtBuildCompatibleExtension.class.getName());

    /**
     * Hook {@code @Enhancement} sur toute classe (et ses sous-types) portant
     * {@code @WithSpan} quelque part (classe ou méthode).
     *
     * <p>Stratégie :</p>
     * <ul>
     *   <li>Si la classe est annotée {@code @WithSpan} → ajoute {@link SpanBinding} sur la classe
     *       (toutes les méthodes deviennent interceptées)</li>
     *   <li>Sinon, parcourt les méthodes : chaque méthode annotée {@code @WithSpan}
     *       reçoit {@link SpanBinding}</li>
     * </ul>
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = WithSpan.class)
    public void addSpanBinding(ClassConfig classConfig) {
        boolean classLevel = classConfig.info().hasAnnotation(WithSpan.class);
        if (classLevel) {
            classConfig.addAnnotation(SpanBinding.class);
            LOG.log(Level.DEBUG, "Ajout @SpanBinding sur la classe {0}", classConfig.info().name());
        }
        for (MethodConfig m : classConfig.methods()) {
            if (m.info().hasAnnotation(WithSpan.class)) {
                m.addAnnotation(SpanBinding.class);
                LOG.log(Level.DEBUG,
                        "Ajout @SpanBinding sur {0}.{1}",
                        classConfig.info().name(), m.info().name());
            }
        }
    }
}
