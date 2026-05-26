package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;

/**
 * Rsout l'ensemble des {@link FtMetricsRecorder} CDI disponibles et les compose si plusieurs.
 *
 * <p>MicroProfile Fault Tolerance §9 (MP Metrics) et §10 (OpenTelemetry) sont des APIs de
 * mtriques distinctes mais simultanment publiables. Quand plusieurs recorders sont
 * prsents ({@link DiracFtMetricsRecorder} + {@link OtelFtMetricsRecorder}), tous sont
 * invoqus en fan-out via {@link CompositeFtMetricsRecorder}, en vitant l'erreur
 * {@code AmbiguousResolutionException} qu'aurait dclenche {@code Instance.get()}.</p>
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

