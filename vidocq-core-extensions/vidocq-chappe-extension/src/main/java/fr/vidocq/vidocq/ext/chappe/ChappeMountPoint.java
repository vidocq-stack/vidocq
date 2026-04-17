package fr.vidocq.vidocq.ext.chappe;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Router;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Point d'accrochage unique partagé entre extensions pour contribuer des handlers au moteur Chappe.
 * <p>
 * Les extensions contributrices ({@code vidocq-servlet-chappe-extension},
 * {@code vidocq-rest-chappe-extension}, ...) appellent
 * {@link #mount(String, String, Handler)} durant leur phase {@code onStart}.
 * {@link ChappeServerBootstrap} assemble ensuite les {@link Router} et démarre un
 * {@link fr.vidocq.chappe.api.Server Server} par listener.
 * </p>
 *
 * <p>Thread-safety : toutes les contributions doivent être faites pendant la phase
 * {@code onStart} (mono-thread orchestrée par {@code VidocqBootstrap}). L'instance est
 * ensuite figée au démarrage du serveur.</p>
 */
public final class ChappeMountPoint {

    private static volatile ChappeMountPoint instance;

    private final Map<String, Router.Builder> routers = new LinkedHashMap<>();
    private final Map<String, ChappeListener> listeners = new LinkedHashMap<>();
    private final List<Runnable> beforeStartHooks = new ArrayList<>();
    private final List<Runnable> afterStopHooks = new ArrayList<>();
    private volatile boolean frozen;

    ChappeMountPoint() {}

    /**
     * Accède à l'instance unique, non-null après le démarrage de {@link ChappeEngineExtension}.
     * <p>Access the singleton. Non-null after {@link ChappeEngineExtension} has started.</p>
     */
    public static ChappeMountPoint instance() {
        ChappeMountPoint i = instance;
        if (i == null) {
            throw new IllegalStateException(
                    "ChappeMountPoint not initialized — ChappeEngineExtension must run first");
        }
        return i;
    }

    static void install(ChappeMountPoint mp) {
        instance = mp;
    }

    static void uninstall() {
        instance = null;
    }

    /**
     * Déclare un listener. Appelé par {@link ChappeServerBootstrap} au démarrage.
     */
    void declareListener(ChappeListener listener) {
        ensureOpen();
        listeners.put(listener.name(), listener);
        routers.computeIfAbsent(listener.name(), n -> Router.builder());
    }

    /**
     * Monte un handler sur le listener par défaut, sous le préfixe donné.
     * <p>Mount a handler on the default listener under the given prefix.</p>
     */
    public void mount(String prefix, Handler handler) {
        mount(ChappeListener.DEFAULT, prefix, handler);
    }

    /**
     * Monte un handler sur un listener nommé.
     * <p>Mount a handler on a named listener.</p>
     */
    public void mount(String listenerName, String prefix, Handler handler) {
        Objects.requireNonNull(listenerName, "listenerName");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(handler, "handler");
        ensureOpen();
        routers.computeIfAbsent(listenerName, n -> Router.builder()).mount(prefix, handler);
    }

    /**
     * Accès direct au {@link Router.Builder} d'un listener, pour contributions fines
     * (routes par méthode, groupes, filtres).
     * <p>Direct access to a listener's {@link Router.Builder} for fine-grained contributions.</p>
     */
    public Router.Builder router(String listenerName) {
        Objects.requireNonNull(listenerName, "listenerName");
        ensureOpen();
        return routers.computeIfAbsent(listenerName, n -> Router.builder());
    }

    /**
     * Hook exécuté juste avant le démarrage de chaque serveur.
     * <p>Hook executed right before each server starts.</p>
     */
    public void addBeforeStartHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        ensureOpen();
        beforeStartHooks.add(hook);
    }

    /**
     * Hook exécuté juste après l'arrêt de chaque serveur.
     * <p>Hook executed right after each server stops.</p>
     */
    public void addAfterStopHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        afterStopHooks.add(hook);
    }

    // --- Accesseurs internes pour ChappeServerBootstrap ---

    Collection<ChappeListener> listeners() {
        return Collections.unmodifiableCollection(listeners.values());
    }

    Router buildRouter(String listenerName) {
        Router.Builder b = routers.get(listenerName);
        return b == null ? Router.builder().build() : b.build();
    }

    List<Runnable> beforeStartHooks() {
        return List.copyOf(beforeStartHooks);
    }

    List<Runnable> afterStopHooks() {
        return List.copyOf(afterStopHooks);
    }

    void freeze() {
        this.frozen = true;
    }

    private void ensureOpen() {
        if (frozen) {
            throw new IllegalStateException(
                    "ChappeMountPoint is frozen — cannot contribute after server start");
        }
    }
}
