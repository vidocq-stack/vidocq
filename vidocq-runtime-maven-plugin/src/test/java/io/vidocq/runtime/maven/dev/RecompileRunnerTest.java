package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link RecompileRunner#detectMavenExecutable(Path)} — the only
 * piece we can verify without spinning a real Maven invocation in unit tests.
 *
 * <p>The end-to-end behaviour of {@link RecompileRunner#run()} is exercised by
 * the manual smoke test on {@code vidocq-runtime-cassini-rest-example}.</p>
 */
class RecompileRunnerTest {

    @Test
    void detect_prefers_local_wrapper_when_present(@TempDir Path tmp) throws Exception {
        Path wrapper = tmp.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));

        String resolved = RecompileRunner.detectMavenExecutable(tmp);
        assertEquals(wrapper.toAbsolutePath().toString(), resolved,
                "should prefer ./mvnw when it exists and is executable");
    }

    @Test
    void detect_falls_back_to_path_when_no_wrapper(@TempDir Path tmp) {
        String resolved = RecompileRunner.detectMavenExecutable(tmp);
        // We accept either "mvn" or "mvn.cmd" depending on the host OS.
        assertTrue(resolved.equals("mvn") || resolved.equals("mvn.cmd"),
                "expected PATH fallback, got: " + resolved);
        assertNotEquals(tmp.resolve("mvnw").toString(), resolved);
    }
}
