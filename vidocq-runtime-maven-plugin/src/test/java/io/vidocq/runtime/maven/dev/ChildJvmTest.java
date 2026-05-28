package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke coverage of {@link ChildJvm}. We can't easily spin a real Vidocq
 * module path from a unit test, but we can verify that the process lifecycle
 * (start → isAlive → stop) behaves correctly against a trivial JVM that just
 * prints {@code -version} and exits.
 */
class ChildJvmTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void start_then_stop_completes_within_grace(@TempDir Path tmp) throws Exception {
        // --module-path emptyDir --module unknown will fail-fast at the JVM
        // level, but the lifecycle (start → exit) still applies and is what
        // we want to exercise here.
        ChildJvm jvm = ChildJvm.of(
                List.of(tmp),
                "no.such.module",
                null,
                List.of("-Xshare:off"),
                Map.of("vidocq.profile", "dev"),
                tmp);

        long pid = jvm.start();
        assertTrue(pid > 0, "spawned PID must be positive");

        // The child will exit on its own (unknown module) — give it time, then
        // call stop() and verify isAlive() flips to false.
        Thread.sleep(1500);
        jvm.stop(Duration.ofSeconds(5));
        assertFalse(jvm.isAlive(), "child JVM should have exited");
    }
}
