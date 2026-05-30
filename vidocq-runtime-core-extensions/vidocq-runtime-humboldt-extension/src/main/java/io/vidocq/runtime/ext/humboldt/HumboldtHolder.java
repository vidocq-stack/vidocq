package io.vidocq.runtime.ext.humboldt;

import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Static holder to expose the instance {@link AutoConfiguredHumboldt}
 * configured by {@link HumboldtExtension} as an injectable CDI bean.
 *
 * <p>Pattern aligned with {@code MansartPoolHolder} and {@code KnockHolder}
 * in the vidocq ecosystem: the extension publishes the instance in
 * {@link #INSTANCE} before {@code addBeanClass(HumboldtHolder.class)},
 * then the {@link #humboldt()} method below exposes it via {@code @Produces}.</p>
 *
 * <p>Allows integration tests to do:</p>
 * <pre>{@code
 * @Inject AutoConfiguredHumboldt humboldt;
 * // ... humboldt.inMemorySpanExporter().getFinishedSpans() ...
 * }</pre>
 */
public class HumboldtHolder {

    /**
     * Singleton reference posted by {@link HumboldtExtension#beforeStart} before
     * registering this class as a bean. {@code null} if the extension
     * is disabled via {@code MP_TELEMETRY_SDK_DISABLED=true}.
     */
    public static volatile AutoConfiguredHumboldt INSTANCE;

    @Produces
    @ApplicationScoped
    public AutoConfiguredHumboldt humboldt() {
        return INSTANCE;
    }
}
