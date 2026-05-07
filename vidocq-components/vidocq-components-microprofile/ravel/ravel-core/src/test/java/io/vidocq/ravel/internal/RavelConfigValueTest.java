/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.ConfigValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RavelConfigValue — métadata d'une lookup (MP Config 3.1 §2.1.5)")
class RavelConfigValueTest {

    @Test
    void normal_construction_exposes_all_fields() {
        // §2.1.5 — getName/getValue/getRawValue/getSourceName/getSourceOrdinal
        // exposent l'origine d'une valeur résolue.
        var v = new RavelConfigValue("server.port", "8080", "8080", "SystemProperties", 400);

        assertInstanceOf(ConfigValue.class, v);
        assertEquals("server.port", v.getName());
        assertEquals("8080", v.getValue());
        assertEquals("8080", v.getRawValue());
        assertEquals("SystemProperties", v.getSourceName());
        assertEquals(400, v.getSourceOrdinal());
    }

    @Test
    void absent_factory_returns_value_with_only_name_set() {
        // §2.1.5 — pour une clé absente, getConfigValue retourne un ConfigValue
        // non-null dont seul `name` est renseigné. Spec : "the returned value
        // will never be null. The metadata of returning ConfigValue should at
        // least show the property name."
        var v = RavelConfigValue.absent("missing.key");

        assertNotNull(v);
        assertEquals("missing.key", v.getName());
        assertNull(v.getValue());
        assertNull(v.getRawValue());
        assertNull(v.getSourceName());
        assertEquals(0, v.getSourceOrdinal());
    }

    @Test
    void absent_factory_rejects_null_name() {
        // Précondition défensive : le name est obligatoire (cf. spec §2.1.5).
        assertThrows(NullPointerException.class, () -> RavelConfigValue.absent(null));
    }

    @Test
    void rawValue_can_differ_from_resolved_value() {
        // rawValue peut contenir la valeur d'origine (ex. expression non résolue),
        // tandis que value contient la valeur finale exposée par Config.
        var v = new RavelConfigValue("greeting", "Hello World", "Hello ${name}", "MPProps", 100);

        assertEquals("Hello World", v.getValue());
        assertEquals("Hello ${name}", v.getRawValue());
    }
}
