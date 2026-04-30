package io.vidocq.mpserver.ext.chappe;

import io.vidocq.chappe.api.Handler;
import io.vidocq.mpserver.ext.chappe.spi.MountConfig;
import io.vidocq.mpserver.ext.chappe.spi.MountHandlerProvider;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqExtension;
import io.vidocq.mpserver.spi.config.VidocqConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Lit la config {@code vidocq.mount.<name>.*} et enregistre chaque mount
 * déclaré sur le {@link ChappeMountPoint} via le {@link MountHandlerProvider}
 * désigné par {@code .type}.
 *
 * <p>Priorité 7 000 — postérieure aux extensions contributrices programmatiques
 * (Cassini : 500, custom apps : généralement < 5000) et antérieure à
 * {@code ChappeServerBootstrap} (10 000) qui démarre les serveurs.</p>
 *
 * <h3>Format de configuration</h3>
 * <pre>{@code
 * vidocq.mount.ui.path          = /
 * vidocq.mount.ui.type          = static
 * vidocq.mount.ui.classpath     = static
 * vidocq.mount.ui.cache-in-memory = true
 *
 * vidocq.mount.api.path         = /api
 * vidocq.mount.api.type         = cassini
 *
 * vidocq.mount.legacy.path      = /srv
 * vidocq.mount.legacy.type      = foy
 * vidocq.mount.legacy.priority  = 200       # défaut : 0
 * vidocq.mount.legacy.listener  = secured   # défaut : "default"
 * }</pre>
 *
 * <p>Les mounts sont enregistrés par <b>priorité décroissante</b> : un mount
 * avec une priorité plus élevée gagne contre un autre qui partagerait un
 * préfixe plus court.</p>
 */
public final class ChappeMountConfigExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(ChappeMountConfigExtension.class.getName());

    private static final String PREFIX = "vidocq.mount.";
    private static final String SUFFIX_PATH = ".path";

    @Override
    public String name() {
        return "chappe-mount-config";
    }

    @Override
    public int priority() {
        return 7_000;
    }

    @Override
    public void onStart(ExtensionContext context) {
        VidocqConfig config = context.config();
        Set<String> mountNames = collectMountNames(config);
        if (mountNames.isEmpty()) {
            return;
        }

        Map<String, MountHandlerProvider> providers = loadProviders();
        List<MountConfig> mounts = new ArrayList<>();

        for (String name : mountNames) {
            String prefix = config.getValue(PREFIX + name + ".path", String.class, "/");
            String listener = config.getValue(PREFIX + name + ".listener", String.class, ChappeListener.DEFAULT);
            int priority = config.getValue(PREFIX + name + ".priority", Integer.class, 0);
            mounts.add(new MountConfig(name, normalizePrefix(prefix), listener, priority, config, context));
        }
        mounts.sort(Comparator.comparingInt(MountConfig::priority).reversed());

        for (MountConfig m : mounts) {
            String type = m.property("type")
                    .orElseThrow(() -> new IllegalStateException(
                            "Mount '" + m.name() + "' is missing required " + PREFIX + m.name() + ".type"));
            MountHandlerProvider provider = providers.get(type);
            if (provider == null) {
                throw new IllegalStateException(
                        "No MountHandlerProvider registered for type='" + type
                                + "' (mount '" + m.name() + "'). "
                                + "Available types: " + providers.keySet());
            }
            Handler handler = provider.create(m);
            ChappeMountPoint.instance().mount(m.listener(), m.prefix(), handler);
            LOG.log(System.Logger.Level.INFO,
                    "Mounted '{0}' (type={1}) on listener={2} prefix={3} priority={4}",
                    m.name(), type, m.listener(), m.prefix().isEmpty() ? "/" : m.prefix(), m.priority());
        }
    }

    private static Set<String> collectMountNames(VidocqConfig config) {
        Set<String> names = new LinkedHashSet<>();
        for (String key : config.getPropertyNames()) {
            if (!key.startsWith(PREFIX) || !key.endsWith(SUFFIX_PATH)) continue;
            String middle = key.substring(PREFIX.length(), key.length() - SUFFIX_PATH.length());
            if (middle.isEmpty() || middle.indexOf('.') >= 0) continue;
            names.add(middle);
        }
        return names;
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "";
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private static Map<String, MountHandlerProvider> loadProviders() {
        Map<String, MountHandlerProvider> map = new HashMap<>();
        for (MountHandlerProvider p : ServiceLoader.load(MountHandlerProvider.class)) {
            MountHandlerProvider previous = map.putIfAbsent(p.type(), p);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two MountHandlerProvider register the same type='" + p.type()
                                + "': " + previous.getClass().getName() + " and " + p.getClass().getName());
            }
        }
        return map;
    }
}
