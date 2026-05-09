/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi.internal;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Qualifiant interne pour distinguer les synthetic beans @ConfigProperties
 * de leurs instances managées découvertes.
 *
 * <p>Utilise un qualifiant distinct avec des attributes BINDABLE (non-Nonbinding)
 * pour éviter les ambiguités CDI et assurer une résolution unique par (type, prefix).</p>
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigPropertiesBeanQualifier {
    /**
     * Préfixe unique pour cette instance du bean.
     * Cet attribut est BINDABLE (utilisé pour la résolution CDI).
     */
    String prefix() default "";

    /**
     * Classe helper pour les littéraux.
     */
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


