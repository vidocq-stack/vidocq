package io.vidocq.dirac.api;

/**
 * Snapshot immuable d'un histogramme Dirac.
 */
public record HistogramSnapshot(
        long count,
        long sum,
        long min,
        long max,
        double mean,
        double p50,
        double p75,
        double p95,
        double p98,
        double p99,
        double p999
) {
}

