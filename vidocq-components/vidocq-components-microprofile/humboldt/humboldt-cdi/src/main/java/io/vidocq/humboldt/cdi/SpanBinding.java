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
package io.vidocq.humboldt.cdi;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Internal {@link InterceptorBinding} — CDI marker used by Humboldt to
 * activate {@link WithSpanInterceptor}.
 *
 * <p>End users should NEVER write {@code @SpanBinding} directly. It is
 * {@link HumboldtBuildCompatibleExtension} that adds it automatically
 * (at CDI build time) on every class or method annotated with
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan} —
 * the standard OpenTelemetry public API annotation expected by the
 * MicroProfile Telemetry 2.1 TCK.</p>
 *
 * <p>The annotation is exposed for technical visibility reasons
 * (the BCE extension can only add publicly accessible annotations),
 * not for application use.</p>
 */
@InterceptorBinding
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpanBinding {
}
