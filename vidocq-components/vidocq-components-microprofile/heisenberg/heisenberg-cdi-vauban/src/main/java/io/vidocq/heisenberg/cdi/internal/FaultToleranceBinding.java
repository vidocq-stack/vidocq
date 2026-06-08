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
package io.vidocq.heisenberg.cdi.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.interceptor.InterceptorBinding;

/**
 * Marker binding for the single Heisenberg {@link FaultToleranceInterceptor}.
 *
 * <p><strong>Why a marker?</strong>
 * Jakarta Interceptors spec §2.6 imposes a logical AND between an interceptor's
 * bindings: an interceptor declaring {@code @Retry} + {@code @Timeout} + ...
 * is activated only if <em>all</em> these annotations are simultaneously present
 * on the bean class/method. Yet MP FT beans generally carry only one FT
 * annotation at a time ({@code @Retry} alone, for example).</p>
 *
 * <p>Pattern aligned with SmallRye Fault Tolerance: an internal marker
 * {@code @FaultToleranceBinding} is the sole binding declared on
 * {@link FaultToleranceInterceptor}, and a BCE {@link HeisenbergExtension}
 * adds it programmatically (via {@code ClassConfig.addAnnotation}) to any
 * class carrying one of the MicroProfile Fault Tolerance 4.1 annotations
 * ({@code @Retry @Timeout @CircuitBreaker @Bulkhead @Asynchronous @Fallback}).</p>
 *
 * <p>The annotation is intentionally reserved for internal use: applications
 * must not apply it manually. Heisenberg guarantees that it appears exactly
 * where an FT policy must apply.</p>
 */
@Inherited
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FaultToleranceBinding {
}

