/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EnvironmentVariablesConfigSource — built-in ord. 300, mapping spec §7.6")
class EnvironmentVariablesConfigSourceTest {

    // -------- Identité --------

    @Test
    void ordinal_is_300() {
        assertEquals(300, new EnvironmentVariablesConfigSource().getOrdinal());
    }

    @Test
    void name_is_canonical() {
        assertEquals("EnvironmentVariablesConfigSource",
                new EnvironmentVariablesConfigSource().getName());
    }

    @Test
    void implements_ConfigSource() {
        assertInstanceOf(ConfigSource.class, new EnvironmentVariablesConfigSource());
    }

    // -------- toEnvFormat (mapping non-alphanum → _) --------

    @ParameterizedTest(name = "[{index}] toEnvFormat({0}) = {1}")
    @CsvSource(quoteCharacter = '\'', value = {
            "'com.ACME.size','com_ACME_size'",
            "'com.ACME-size','com_ACME_size'",
            "'MY_VAR_42','MY_VAR_42'",
            "'path','path'",
            "'a.b.c.d','a_b_c_d'",
            "'   ','___'",
            "'com.foo/bar:baz','com_foo_bar_baz'"
    })
    void toEnvFormat_replaces_non_alphanumeric_with_underscore(String input, String expected) {
        // §7.6 — Replace each character that is neither alphanumeric nor _ with _
        assertEquals(expected, EnvironmentVariablesConfigSource.toEnvFormat(input));
    }

    @Test
    void toEnvFormat_empty_input() {
        assertEquals("", EnvironmentVariablesConfigSource.toEnvFormat(""));
    }

    // -------- 3 formes essayées dans l'ordre (§7.6) --------

    @Test
    void form1_exact_match_wins() {
        // §7.6 forme 1 — clé telle quelle.
        Map<String, String> env = Map.of("com.ACME.size", "exact",
                                          "com_ACME_size", "form2",
                                          "COM_ACME_SIZE", "form3");
        assertEquals("exact",
                EnvironmentVariablesConfigSource.lookup("com.ACME.size", env::get));
    }

    @Test
    void form2_non_alphanum_to_underscore() {
        // §7.6 forme 2 — non-alphanum → _, casse préservée.
        Map<String, String> env = Map.of("com_ACME_size", "form2",
                                          "COM_ACME_SIZE", "form3");
        assertEquals("form2",
                EnvironmentVariablesConfigSource.lookup("com.ACME.size", env::get));
    }

    @Test
    void form3_uppercase_fallback() {
        // §7.6 forme 3 — étape 2 puis upper case.
        Map<String, String> env = Map.of("COM_ACME_SIZE", "form3");
        assertEquals("form3",
                EnvironmentVariablesConfigSource.lookup("com.ACME.size", env::get));
    }

    @Test
    void absent_returns_null() {
        Map<String, String> env = Map.of("OTHER", "x");
        assertNull(EnvironmentVariablesConfigSource.lookup("com.ACME.size", env::get));
    }

    @Test
    void already_compatible_key_resolves_via_form1() {
        Map<String, String> env = Map.of("MY_VAR", "v");
        assertEquals("v", EnvironmentVariablesConfigSource.lookup("MY_VAR", env::get));
    }

    // -------- Lookup réel via System.getenv (variable PATH toujours présente) --------

    @Test
    void real_lookup_PATH_via_getValue_form1() {
        // Sur macOS/Linux/Windows, PATH existe systématiquement et est lisible
        // tel quel via System.getenv (forme 1 exact).
        var src = new EnvironmentVariablesConfigSource();
        String pathExact = System.getenv("PATH");
        if (pathExact != null) {
            assertEquals(pathExact, src.getValue("PATH"));
        }
    }

    // -------- getPropertyNames --------

    @Test
    void getPropertyNames_returns_raw_env_keys() {
        // §7.6 — "The implementation MUST return all the keys it knows of, ie returning the keys as is."
        var src = new EnvironmentVariablesConfigSource();
        var names = src.getPropertyNames();
        // au moins une variable d'environnement doit être présente
        assertTrue(!names.isEmpty(), "L'environnement de test doit avoir au moins une variable");
    }
}
