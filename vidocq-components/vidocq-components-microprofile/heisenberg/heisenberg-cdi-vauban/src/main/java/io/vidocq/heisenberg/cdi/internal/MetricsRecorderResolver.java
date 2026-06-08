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

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves all available CDI {@link FtMetricsRecorder} implementations and composes them if several exist.
 *
 * <p>MicroProfile Fault Tolerance §9 (MP Metrics) and §10 (OpenTelemetry) are distinct
 * metrics APIs but can be published simultaneously. When several recorders are
 * present ({@link DiracFtMetricsRecorder} + {@link OtelFtMetricsRecorder}), all are
 * invoked in fan-out via {@link CompositeFtMetricsRecorder}, avoiding the
 * {@code AmbiguousResolutionException} that {@code Instance.get()} would have triggered.</p>
 */
final class MetricsRecorderResolver {

    private MetricsRecorderResolver() {}

    static FtMetricsRecorder resolve(Instance<FtMetricsRecorder> recorderInstance) {
        if (recorderInstance == null || recorderInstance.isUnsatisfied()) {
            return FtMetricsRecorder.NOOP;
        }
        List<FtMetricsRecorder> recorders = new ArrayList<>();
        for (FtMetricsRecorder r : recorderInstance) {
            if (r != null) recorders.add(r);
        }
        if (recorders.isEmpty()) return FtMetricsRecorder.NOOP;
        if (recorders.size() == 1) return recorders.get(0);
        return new CompositeFtMetricsRecorder(recorders);
    }
}

