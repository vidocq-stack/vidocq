package io.vidocq.humboldt.sdk.common;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Squelette de batch processor mutualisé entre les signaux trace et log.
 *
 * <p>Worker virtual thread + queue bornée + 4 déclencheurs d'export :</p>
 * <ul>
 *   <li>la queue atteint {@code maxExportBatchSize}</li>
 *   <li>{@code scheduleDelay} écoulé depuis le dernier batch</li>
 *   <li>appel explicite à {@link #flushBase()}</li>
 *   <li>{@link #shutdownBase()} — drain + export final</li>
 * </ul>
 *
 * <p>Les sous-classes appellent {@link #offer(Object)} depuis leur callback
 * ({@code onEnd} pour SpanProcessor, {@code onEmit} pour LogRecordProcessor)
 * et fournissent le {@link Consumer} d'export batch via le constructeur.</p>
 *
 * <p>Le worker s'exécute sur un virtual thread ({@link Thread#ofVirtual()}) —
 * pas de pinning de carrier thread sur opérations bloquantes Java pures
 * (cf. JEP 444).</p>
 *
 * @param <T> type d'élément (SpanData ou LogRecordData typiquement)
 */
public abstract class AbstractBatchProcessor<T> {

    private static final Logger LOG = System.getLogger(AbstractBatchProcessor.class.getName());

    private final String workerName;
    private final int maxExportBatchSize;
    private final long scheduleDelayNanos;
    private final LinkedBlockingQueue<T> queue;
    private final Consumer<List<T>> exportBatch;
    private final Runnable shutdownExporter;
    private final java.util.function.Supplier<CompletableResultCode> flushExporter;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final Object flushLock = new Object();
    private volatile CompletableResultCode pendingFlush;

    /**
     * @param workerName        nom du virtual thread (ex. {@code "humboldt-batch-span-processor"})
     * @param maxQueueSize      taille max de la queue (offer retourne false au-delà)
     * @param maxExportBatchSize taille max d'un batch envoyé à {@code exportBatch}
     * @param scheduleDelay     délai max entre 2 batchs auto
     * @param exportBatch       callback appelé par le worker pour exporter un batch
     * @param flushExporter     callback appelé à {@link #flushBase()} après drain
     * @param shutdownExporter  callback appelé à {@link #shutdownBase()} pour libérer l'exporter
     */
    protected AbstractBatchProcessor(
            String workerName,
            int maxQueueSize,
            int maxExportBatchSize,
            Duration scheduleDelay,
            Consumer<List<T>> exportBatch,
            java.util.function.Supplier<CompletableResultCode> flushExporter,
            Runnable shutdownExporter) {
        this.workerName = workerName;
        this.maxExportBatchSize = maxExportBatchSize;
        this.scheduleDelayNanos = scheduleDelay.toNanos();
        this.queue = new LinkedBlockingQueue<>(maxQueueSize);
        this.exportBatch = exportBatch;
        this.flushExporter = flushExporter;
        this.shutdownExporter = shutdownExporter;
        this.worker = Thread.ofVirtual()
                .name(workerName)
                .start(this::workerLoop);
    }

    /** À appeler depuis le callback de la sous-classe (onEnd/onEmit). */
    protected final void offer(T item) {
        if (!running.get()) return;
        if (!queue.offer(item)) {
            LOG.log(Level.WARNING, "{0} queue saturée — élément dropé", workerName);
        }
    }

    protected final CompletableResultCode flushBase() {
        if (queue.isEmpty()) return flushExporter.get();
        synchronized (flushLock) {
            CompletableResultCode code = new CompletableResultCode();
            pendingFlush = code;
            flushLock.notifyAll();
            return code;
        }
    }

    protected final CompletableResultCode shutdownBase() {
        if (!running.compareAndSet(true, false)) return CompletableResultCode.ofSuccess();
        synchronized (flushLock) {
            flushLock.notifyAll();
        }
        try {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        shutdownExporter.run();
        return CompletableResultCode.ofSuccess();
    }

    private void workerLoop() {
        List<T> batch = new ArrayList<>(maxExportBatchSize);
        long lastExportNanos = System.nanoTime();
        while (running.get() || !queue.isEmpty()) {
            long now = System.nanoTime();
            long waitNanos = scheduleDelayNanos - (now - lastExportNanos);
            if (waitNanos > 0 && queue.size() < maxExportBatchSize) {
                synchronized (flushLock) {
                    if (pendingFlush == null && running.get() && queue.size() < maxExportBatchSize) {
                        try {
                            TimeUnit.NANOSECONDS.timedWait(flushLock, waitNanos);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            queue.drainTo(batch, maxExportBatchSize);
            if (!batch.isEmpty()) {
                exportBatchSafe(batch);
                batch.clear();
                lastExportNanos = System.nanoTime();
            }
            CompletableResultCode requested;
            synchronized (flushLock) {
                requested = pendingFlush;
                pendingFlush = null;
            }
            if (requested != null) {
                queue.drainTo(batch);
                if (!batch.isEmpty()) {
                    exportBatchSafe(batch);
                    batch.clear();
                }
                requested.succeed();
            }
        }
    }

    private void exportBatchSafe(List<T> batch) {
        try {
            exportBatch.accept(List.copyOf(batch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Échec export batch " + workerName + " (" + batch.size() + " items)", e);
        }
    }
}
