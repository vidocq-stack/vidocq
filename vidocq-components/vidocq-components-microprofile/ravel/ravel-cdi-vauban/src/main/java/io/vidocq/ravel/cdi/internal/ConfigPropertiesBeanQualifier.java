/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi.internal;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Internal qualifier used to distinguish synthetic {@code @ConfigProperties}
 * beans from discovered managed instances.
 *
 * <p>Uses a dedicated qualifier with BINDABLE attributes to avoid CDI
 * ambiguity and ensure unique resolution per (type, prefix).</p>
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigPropertiesBeanQualifier {
    /**
     * Unique prefix for this bean instance.
     * This attribute is BINDABLE (used for CDI resolution).
     */
    String prefix() default "";

    /** Helper class for qualifier literals. */
    final class Literal {
        private Literal() {
        }

        public static ConfigPropertiesBeanQualifier of(String prefix) {
            return new ConfigPropertiesBeanQualifier() {
                @Override
                public String prefix() {
                    return prefix;
                }

                @Override
                public Class<? extends ConfigPropertiesBeanQualifier> annotationType() {
                    return ConfigPropertiesBeanQualifier.class;
                }
            };
        }
    }
}


