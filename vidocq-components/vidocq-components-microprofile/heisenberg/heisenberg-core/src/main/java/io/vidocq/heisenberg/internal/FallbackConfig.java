package io.vidocq.heisenberg.internal;

import org.eclipse.microprofile.faulttolerance.FallbackHandler;

/**
 * Immutable configuration for the {@code @Fallback} policy.
 *
 * <p>MP FT 4.1 §6 and §9: only {@code applyOn} and {@code skipOn} can be overridden
 * through MicroProfile Config. The {@code value} (handler class) and {@code fallbackMethod}
 * are fixed at compile time to allow their validation at container startup.</p>
 *
 * @param applyOn types of exception that trigger the fallback (spec default: {@link Throwable})
 * @param skipOn  types of exception that bypass the fallback and are propagated as-is
 * @param fallbackMethod name of the effective fallbackMethod (annotation or config override)
 * @param fallbackHandlerClass type of the effective FallbackHandler (annotation or config override)
 */
public record FallbackConfig(
        Class<? extends Throwable>[] applyOn,
        Class<? extends Throwable>[] skipOn,
        String fallbackMethod,
        Class<? extends FallbackHandler<?>> fallbackHandlerClass
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
     * Indicates whether {@code failure} should trigger the fallback according to the {@code applyOn}/{@code skipOn} rules.
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

