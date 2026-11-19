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
 * Humboldt SDK Log — OpenTelemetry implementation of the {@code logs} signal.
 *
 * <p>M5 MVP: SdkLoggerProvider, SdkLogger, SdkLogRecordBuilder, Simple + Batch
 * processors (virtual-thread worker).</p>
 *
 * <p>Deferred to M5b: {@code java.util.logging} and {@code SLF4J} bridges,
 * appender-based sources (to capture logs from existing applications without
 * rewriting the calls).</p>
 */
module io.vidocq.humboldt.sdk.log {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.common;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires java.logging;

    exports io.vidocq.humboldt.sdk.log;
    exports io.vidocq.humboldt.sdk.log.bridge;
    exports io.vidocq.humboldt.sdk.log.data;
    exports io.vidocq.humboldt.sdk.log.export;
}
