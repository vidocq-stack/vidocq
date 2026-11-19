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
 * Knock API: controlled re-exposure of the MicroProfile Health 4.0 spec and a stable
 * public SPI. The content will be expanded as milestones progress.
 *
 * <p><strong>Java Modules note — Vidocq fork of the MicroProfile Health API</strong>
 * (see {@code docs/adr/ADR-001-java-modules-workaround-microprofile-health.md}) :</p>
 *
 * <p>Knock depends on a fork `io.vidocq.knock:knock-mp-health-api` that includes
 * a {@code module-info.class}. The module name remains {@code microprofile.health.api},
 * identical to upstream, which preserves {@code requires} compatibility.</p>
 */
module io.vidocq.knock.api {
    requires transitive microprofile.health.api;

    exports io.vidocq.knock.spi;
}
