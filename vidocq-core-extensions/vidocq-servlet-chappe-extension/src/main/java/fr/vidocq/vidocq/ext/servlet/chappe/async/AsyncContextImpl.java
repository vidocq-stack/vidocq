package fr.vidocq.vidocq.ext.servlet.chappe.async;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link AsyncContext} Jakarta Servlet 6.1.
 *
 * <p>Contrat : le bridge appelle {@link #awaitCompletion()} après le retour du
 * service() du servlet, ce qui bloque le thread Chappe jusqu'à :</p>
 * <ul>
 *   <li>{@link #complete()} — la réponse est matérialisée en l'état</li>
 *   <li>{@link #dispatch(String)} — un redispatch {@link jakarta.servlet.DispatcherType#ASYNC} est planifié</li>
 *   <li>expiration du {@link #setTimeout timeout} — déclenche onTimeout</li>
 * </ul>
 *
 * <p>Les {@link AsyncListener} reçoivent les événements onStartAsync (non émis au premier
 * startAsync, seulement si {@code startAsync} est ré-appelé après un dispatch, cf. §2.3.3.3),
 * onComplete, onTimeout, onError.</p>
 */
public final class AsyncContextImpl implements AsyncContext {

    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private static final ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final ServletRequest request;
    private final ServletResponse response;
    private final ServletContext servletContext;
    private final boolean originalRequestAndResponse;
    private final List<ListenerRegistration> listeners = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private volatile long timeoutMs = DEFAULT_TIMEOUT_MS;
    private volatile String dispatchPath;
    private volatile boolean completed;
    private volatile boolean timedOut;

    private record ListenerRegistration(AsyncListener listener,
                                        ServletRequest suppliedReq,
                                        ServletResponse suppliedRes) {}

    public AsyncContextImpl(ServletRequest request, ServletResponse response,
                            ServletContext servletContext, boolean originalRequestAndResponse) {
        this.request = request;
        this.response = response;
        this.servletContext = servletContext;
        this.originalRequestAndResponse = originalRequestAndResponse;
    }

    @Override public ServletRequest getRequest() { return request; }
    @Override public ServletResponse getResponse() { return response; }
    @Override public boolean hasOriginalRequestAndResponse() { return originalRequestAndResponse; }

    @Override public void dispatch() {
        String uri = request instanceof jakarta.servlet.http.HttpServletRequest h
                ? h.getRequestURI() : null;
        dispatch(uri);
    }

    @Override public void dispatch(String path) { dispatch(servletContext, path); }

    @Override public void dispatch(ServletContext ctx, String path) {
        if (completed) throw new IllegalStateException("async already completed");
        this.dispatchPath = path;
        completeInternal(false);
    }

    @Override public void complete() {
        if (completed) return;
        completeInternal(false);
    }

    @Override public void start(Runnable run) {
        VIRTUAL_EXECUTOR.execute(() -> {
            try { run.run(); }
            catch (Throwable t) { fireOnError(t); }
        });
    }

    @Override public void addListener(AsyncListener listener) {
        addListener(listener, request, response);
    }

    @Override public void addListener(AsyncListener listener, ServletRequest req, ServletResponse res) {
        listeners.add(new ListenerRegistration(listener, req, res));
    }

    @Override public <T extends AsyncListener> T createListener(Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public void setTimeout(long timeout) { this.timeoutMs = timeout; }
    @Override public long getTimeout() { return timeoutMs; }

    /** Bloque le thread appelant jusqu'à complete(), dispatch() ou expiration. */
    public void awaitCompletion() {
        try {
            if (timeoutMs <= 0) {
                completion.get();
            } else {
                completion.get(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            timedOut = true;
            fireOnTimeout();
            completeInternal(true);
        } catch (ExecutionException e) {
            fireOnError(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean hasDispatch() { return dispatchPath != null; }
    public String dispatchPath() { return dispatchPath; }
    public boolean timedOut() { return timedOut; }
    public boolean isCompleted() { return completed; }

    private void completeInternal(boolean fromTimeout) {
        if (completed) return;
        completed = true;
        if (!fromTimeout) fireOnComplete();
        completion.complete(null);
    }

    private void fireOnComplete() {
        for (var reg : listeners) {
            try { reg.listener().onComplete(newEvent(reg, null)); }
            catch (IOException ignored) {}
        }
    }

    private void fireOnTimeout() {
        for (var reg : listeners) {
            try { reg.listener().onTimeout(newEvent(reg, null)); }
            catch (IOException ignored) {}
        }
    }

    private void fireOnError(Throwable t) {
        for (var reg : listeners) {
            try { reg.listener().onError(newEvent(reg, t)); }
            catch (IOException ignored) {}
        }
    }

    private AsyncEvent newEvent(ListenerRegistration reg, Throwable cause) {
        return new AsyncEvent(this, reg.suppliedReq(), reg.suppliedRes(), cause);
    }
}
