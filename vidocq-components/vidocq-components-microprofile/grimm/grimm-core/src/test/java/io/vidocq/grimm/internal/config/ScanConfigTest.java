/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.grimm.internal.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScanConfig}.
 *
 * Spec §4.1: Tests for mp.openapi.scan.* configuration filtering.
 */
class ScanConfigTest {

    @Test
    void defaultConfig_scansAll() {
        // Default config should scan all classes
        ScanConfig config = ScanConfig.defaultConfig();
        assertTrue(config.shouldScan("com.example.MyClass"));
        assertTrue(config.shouldScan("org.other.Resource"));
    }

    @Test
    void disableScan_preventsAllScanning() {
        // When disableScan=true, no classes should be scanned
        ScanConfig config = new ScanConfig(true, Set.of(), Set.of(), Set.of(), Set.of());
        assertFalse(config.shouldScan("com.example.MyClass"));
    }

    @Test
    void includePackages_filtersClasses() {
        // Spec §4.1: mp.openapi.scan.packages restricts to specific packages
        ScanConfig config = new ScanConfig(
            false,
            Set.of("com.example", "org.test"),
            Set.of(),
            Set.of(),
            Set.of()
        );
        assertTrue(config.shouldScan("com.example.MyClass"));
        assertTrue(config.shouldScan("org.test.Resource"));
        assertFalse(config.shouldScan("com.other.Class"));
    }

    @Test
    void includePackages_includesSubpackages() {
        // Package matching should include subpackages
        ScanConfig config = new ScanConfig(
            false,
            Set.of("com.example"),
            Set.of(),
            Set.of(),
            Set.of()
        );
        assertTrue(config.shouldScan("com.example.controller.UserController"));
        assertFalse(config.shouldScan("com.notexample.Class"));
    }

    @Test
    void excludeClasses_removesSpecificClasses() {
        // Spec §4.1: mp.openapi.scan.exclude.classes removes specific classes
        ScanConfig config = new ScanConfig(
            false,
            Set.of("com.example"),
            Set.of(),
            Set.of(),
            Set.of("com.example.Hidden")
        );
        assertTrue(config.shouldScan("com.example.MyClass"));
        assertFalse(config.shouldScan("com.example.Hidden"));
    }

    @Test
    void excludePackages_removesPackages() {
        // Spec §4.1: mp.openapi.scan.exclude.packages removes entire packages
        ScanConfig config = new ScanConfig(
            false,
            Set.of(),
            Set.of(),
            Set.of("com.internal"),
            Set.of()
        );
        assertTrue(config.shouldScan("com.example.MyClass"));
        assertFalse(config.shouldScan("com.internal.Secret"));
        assertFalse(config.shouldScan("com.internal.impl.Detail"));
    }

    @Test
    void includeClasses_worksWithPackages() {
        // Include classes can be combined with include packages
        ScanConfig config = new ScanConfig(
            false,
            Set.of("com.example"),
            Set.of("org.other.Special"),
            Set.of(),
            Set.of()
        );
        assertTrue(config.shouldScan("com.example.Class"));
        assertTrue(config.shouldScan("org.other.Special"));
        assertFalse(config.shouldScan("org.other.Regular"));
    }

    @Test
    void excludeOverridesInclude() {
        // Exclusion takes priority over inclusion
        ScanConfig config = new ScanConfig(
            false,
            Set.of("com.example"),
            Set.of(),
            Set.of("com.example.admin"),
            Set.of()
        );
        assertTrue(config.shouldScan("com.example.user.UserClass"));
        assertFalse(config.shouldScan("com.example.admin.AdminClass"));
    }

    @Test
    void includeClass_overridesPackageExclusion() {
        ScanConfig config = new ScanConfig(
            false,
            Set.of(),
            Set.of("com.example.api.ImportantResource"),
            Set.of("com.example"),
            Set.of()
        );
        assertTrue(config.shouldScan("com.example.api.ImportantResource"));
    }

    @Test
    void mostSpecificPackageRuleWins() {
        ScanConfig config = new ScanConfig(
            false,
            Set.of("com.example", "com.example.admin.safe"),
            Set.of(),
            Set.of("com.example.admin"),
            Set.of()
        );
        assertTrue(config.shouldScan("com.example.user.UserResource"));
        assertFalse(config.shouldScan("com.example.admin.AdminResource"));
        assertTrue(config.shouldScan("com.example.admin.safe.SafeResource"));
    }
}

