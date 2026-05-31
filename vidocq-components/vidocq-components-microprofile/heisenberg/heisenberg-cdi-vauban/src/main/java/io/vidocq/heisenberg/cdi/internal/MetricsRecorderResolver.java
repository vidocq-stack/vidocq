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

