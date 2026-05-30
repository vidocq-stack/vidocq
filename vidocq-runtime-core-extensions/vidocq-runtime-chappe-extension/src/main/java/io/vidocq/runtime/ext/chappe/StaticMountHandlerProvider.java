package io.vidocq.runtime.ext.chappe;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.runtime.ext.chappe.spi.MountConfig;
import io.vidocq.runtime.ext.chappe.spi.MountHandlerProvider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Provider builtin serving static content via {@link StaticFileHandler}.
 *
 * <h3>Supported properties</h3>
 * <pre>{@code
 * vidocq.http.mount.<name>.type = static
 * vidocq.http.mount.<name>.path = / # HTTP prefix (declared on the reader side)
 * vidocq.http.mount.<name>.classpath = static # base classpath (mutually exclusive with filesystem)
 * vidocq.http.mount.<name>.filesystem = /var/www/ui # base filesystem (mutually exclusive with classpath)
 * vidocq.http.mount.<name>.index = index.html # index name (default: index.html)
 * vidocq.http.mount.<name>.cache-control = max-age=3600 # header Cache-Control (optional)
 * vidocq.http.mount.<name>.cache-in-memory= true # default: false
 * }</pre>
 */
public final class StaticMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "static";
    }

    @Override
    public Handler create(MountConfig cfg) {
        StaticFileHandler.Builder b = StaticFileHandler.builder()
                .indexFile(cfg.property("index", String.class, "index.html"));

        Optional<String> classpath = cfg.property("classpath");
        Optional<String> filesystem = cfg.property("filesystem");

        if (classpath.isPresent() && filesystem.isPresent()) {
            throw new IllegalStateException(
                    "Mount '" + cfg.name() + "' declares both classpath and filesystem — pick one.");
        }
        if (classpath.isEmpty() && filesystem.isEmpty()) {
            throw new IllegalStateException(
                    "Mount '" + cfg.name() + "' (type=static) requires either "
                            + "vidocq.http.mount." + cfg.name() + ".classpath or .filesystem");
        }
        classpath.ifPresent(b::addClasspath);
        filesystem.ifPresent(p -> b.addPath(Path.of(p)));

        cfg.property("cache-control").ifPresent(b::cacheControl);
        b.cacheInMemory(cfg.property("cache-in-memory", Boolean.class, Boolean.FALSE));

        return b.build();
    }
}
