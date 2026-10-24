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
package io.vidocq.runtime.junit;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Starts the application before the first test of the class and stops it after the last one. The application runs
 * in its own JVM, booted by the runtime's own main from the module path of the tests, with its HTTP listener on a
 * free port of {@code 127.0.0.1}; a test method gets its URL through a {@link VidocqUrl} parameter, or the
 * {@link VidocqApplication} itself.
 *
 * <pre>{@code
 * @VidocqTest
 * class HelloResourceTest {
 *     @Test
 *     void saysHello(@VidocqUrl URI base) throws Exception { … }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(VidocqTestExtension.class)
public @interface VidocqTest {

    /** System properties of the application, {@code key=value} each; they win over {@code vidocq.properties}. */
    String[] properties() default {};

    /**
     * The application module. By default the module of the test class, which Surefire and the IDEs patch into the
     * application module, else {@code -Dvidocq.mainModule}.
     */
    String module() default "";

    /** How long the application may take to listen, in seconds. */
    int startupTimeoutSeconds() default 60;
}
