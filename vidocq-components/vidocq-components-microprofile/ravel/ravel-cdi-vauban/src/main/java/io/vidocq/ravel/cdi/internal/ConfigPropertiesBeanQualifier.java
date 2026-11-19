/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
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


