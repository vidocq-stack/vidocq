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
 * Stable public SPI of Cervantes and re-exposure of the spec MicroProfile JWT 2.1.
 *
 * <p>Export {@code io.vidocq.cervantes.api}: the transport-agnostic SPI shared by the core,
 * CDI integration and JAX-RS integration ({@code JwtValidator}, {@code KeyResolver},
 * {@code JwtConfig}, {@code SignatureAlgorithm}, {@code JwtValidationException}). Reexposed
 * transitivement la spec {@code org.eclipse.microprofile.jwt}.</p>
 */
module io.vidocq.cervantes.api {
    requires transitive org.eclipse.microprofile.jwt;

    exports io.vidocq.cervantes.api;
}
