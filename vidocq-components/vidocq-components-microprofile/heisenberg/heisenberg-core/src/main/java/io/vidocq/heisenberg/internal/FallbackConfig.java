package io.vidocq.heisenberg.internal;

/**
 * Configuration immuable pour la politique {@code @Fallback}.
 *
 * <p>MP FT 4.1 §6 et §9 : seuls {@code applyOn} et {@code skipOn} sont surchargeables
 * via MicroProfile Config. Le {@code value} (handler class) et la {@code fallbackMethod}
 * sont fixés à la compilation pour permettre leur validation au démarrage du container.</p>
 *
 * @param applyOn types d'exception qui déclenchent le fallback (défaut spec : {@link Throwable})
 * @param skipOn  types d'exception qui contournent le fallback et sont propagées telles quelles
 */
public record FallbackConfig(
        Class<? extends Throwable>[] applyOn,
        Class<? extends Throwable>[] skipOn
) {

    public FallbackConfig {
        if (applyOn == null) {
            throw new IllegalArgumentException("applyOn must not be null");
        }
        if (skipOn == null) {
            throw new IllegalArgumentException("skipOn must not be null");
        }
    }

    /**
     * Indique si {@code failure} doit déclencher le fallback selon les règles {@code applyOn}/{@code skipOn}.
     */
    public boolean shouldApplyFallback(Throwable failure) {
        for (Class<? extends Throwable> declaredType : skipOn) {
            if (declaredType.isAssignableFrom(failure.getClass())) {
                return false;
            }
        }
        for (Class<? extends Throwable> declaredType : applyOn) {
            if (declaredType.isAssignableFrom(failure.getClass())) {
                return true;
            }
        }
        return false;
    }
}

