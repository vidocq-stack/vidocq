package io.vidocq.mpserver.ext.humboldt;

import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Holder statique pour exposer l'instance {@link AutoConfiguredHumboldt}
 * configurée par {@link HumboldtExtension} comme bean CDI injectable.
 *
 * <p>Pattern aligné sur {@code MansartPoolHolder} et {@code KnockHolder}
 * dans l'écosystème vidocq-mps : l'extension publie l'instance dans
 * {@link #INSTANCE} avant {@code addBeanClass(HumboldtHolder.class)},
 * puis la méthode {@link #humboldt()} ci-dessous l'expose via {@code @Produces}.</p>
 *
 * <p>Permet aux tests d'intégration de faire :</p>
 * <pre>{@code
 * @Inject AutoConfiguredHumboldt humboldt;
 * // ... humboldt.inMemorySpanExporter().getFinishedSpans() ...
 * }</pre>
 */
public class HumboldtHolder {

    /**
     * Référence singleton publiée par {@link HumboldtExtension#beforeStart} avant
     * l'enregistrement de cette classe comme bean. {@code null} si l'extension
     * est désactivée via {@code MP_TELEMETRY_SDK_DISABLED=true}.
     */
    public static volatile AutoConfiguredHumboldt INSTANCE;

    @Produces
    @ApplicationScoped
    public AutoConfiguredHumboldt humboldt() {
        return INSTANCE;
    }
}
