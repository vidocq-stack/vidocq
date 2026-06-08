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
package io.vidocq.runtime.it.humboldt;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Target CDI bean — verifies that the {@code BuildCompatibleExtension} of
 * {@code humboldt-cdi} adds the binding {@code @SpanBinding} on the
 * annotated methods {@link WithSpan @WithSpan} OTel, which activates
 * {@code WithSpanInterceptor} upon invocation.
 */
@ApplicationScoped
public class TraceTestService {

    @WithSpan("traced.work")
    public String doWork() {
        return "work-result";
    }

    @WithSpan(value = "traced.boom", kind = SpanKind.INTERNAL)
    public String alwaysFails() {
        throw new IllegalStateException("boom from traced.boom");
    }

    /** NO annotation method — checks for absence of span for non-annotated ones. */
    public String plain() {
        return "plain-result";
    }
}
