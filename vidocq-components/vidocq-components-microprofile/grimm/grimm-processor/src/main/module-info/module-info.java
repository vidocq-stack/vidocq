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
/**
 * APT annotation processor generating {@code <Resource>$$GrimmModel} companion sources
 * for JAX-RS resource classes (codegen audit CG-06, APT-first rule, milestone M1).
 *
 * <p>The processor builds the per-class OpenAPI fragment with grimm-core's <em>real</em>
 * model objects and schema generator inside the javac JVM, serializes it with grimm's
 * own serializer, and embeds the JSON literal in the companion — the generated code
 * depends only on the exported {@code spi.gen} contract.</p>
 *
 * <p><strong>M1 scope</strong>: structural JAX-RS only — class/method {@code @Path},
 * HTTP verbs, {@code @PathParam}/{@code @QueryParam}/{@code @HeaderParam} of scalar
 * types, {@code @Produces}, inferred 200 responses. Any class using MicroProfile
 * OpenAPI annotations, request bodies, sub-resource locators, non-scalar types or any
 * other construct outside this subset is skipped with a compiler NOTE — the runtime
 * {@code AnnotationScanner} remains the documented fallback and the behavioural
 * reference (oracle of the equivalence tests).</p>
 */
module io.vidocq.grimm.processor {
    requires java.compiler;
    requires io.vidocq.grimm.core;
    requires jakarta.ws.rs;
    requires org.eclipse.microprofile.openapi;

    // No exports: an APT processor is consumed exclusively through the
    // javax.annotation.processing.Processor SPI below (javac ServiceLoader).
    provides javax.annotation.processing.Processor
            with io.vidocq.grimm.processor.GrimmModelProcessor;
}
