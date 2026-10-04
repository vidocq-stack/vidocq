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
 * Cervantes JWT validation engine — pure Java 25, without CDI or JAX-RS.
 *
 * <p>Decoding ({@link io.vidocq.cervantes.internal.JwtParser}), RSA/ECDSA signature
 * check via the JCA ({@link io.vidocq.cervantes.internal.JwtSignatureVerifier},
 * {@link io.vidocq.cervantes.internal.EcdsaSignatures}), claim validation
 * ({@link io.vidocq.cervantes.internal.JwtClaimsValidator}), PEM key loading
 * ({@link io.vidocq.cervantes.internal.PemKeys}) and orchestration
 * ({@link io.vidocq.cervantes.internal.DefaultJwtValidator}). Internal package: not exported
 * without qualification (consumed by CDI and JAX-RS integrations from M3/M4).</p>
 */
package io.vidocq.cervantes.internal;
