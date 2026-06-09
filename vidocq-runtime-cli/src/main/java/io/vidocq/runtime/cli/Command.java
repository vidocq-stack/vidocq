package io.vidocq.runtime.cli;

import java.nio.file.Path;
import java.util.Set;

/**
 * Sealed hierarchy of every command the Vidocq CLI understands.
 * CliParser produces one instance; CommandRunner dispatches on it.
 */
public sealed interface Command {

    record Version() implements Command {}

    record Info() implements Command {}

    record Help(String topic) implements Command {}

    record Start(int port, Path configFile, boolean debug) implements Command {
        static Start defaults() { return new Start(8080, null, false); }
    }

    record Dev(int port, boolean debug) implements Command {
        static Dev defaults() { return new Dev(8080, false); }
    }

    record Create(
            String name,
            String groupId,
            String pkg,
            Set<String> extensions
    ) implements Command {}

    sealed interface Extension extends Command {
        record Listing(boolean installed, boolean available) implements Extension {
            static Listing defaults() { return new Listing(true, false); }
        }
        record Add(java.util.List<String> ids) implements Extension {}
        record Remove(java.util.List<String> ids) implements Extension {}
    }
}
