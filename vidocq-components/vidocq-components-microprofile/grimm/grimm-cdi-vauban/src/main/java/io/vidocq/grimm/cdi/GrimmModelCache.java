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
package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.ModelBuilder;
import io.vidocq.grimm.internal.config.GrimmConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds the assembled {@link OpenAPI} document for the lifetime of the application.
 *
 * <p>Spec §2.1: the {@code /openapi} endpoint must serve a document that is consistent
 * across requests. The cache is populated once at container startup via
 * {@link #initialize(GrimmConfig, Collection)} and then served read-only.</p>
 *
 * <p>Thread-safe via {@link ReentrantLock} (no {@code synchronized} — virtual-thread friendly).</p>
 */
@ApplicationScoped
public class GrimmModelCache {

    private final ReentrantLock lock = new ReentrantLock();
    private final ModelBuilder modelBuilder = new ModelBuilder();
    private volatile OpenAPI document;
    private final GrimmConfig config;
    private final ScannedTypes scannedTypes;

    /** CDI constructor. */
    @Inject
    public GrimmModelCache(GrimmConfig config, Instance<ScannedTypes> scannedTypes) {
        // An Instance: ScannedTypes is a synthetic bean of GrimmExtension, which exists once the
        // extension ran in this container, not when the Vauban processor validates the module.
        this(config, scannedTypes != null && scannedTypes.isResolvable() ? scannedTypes.get() : ScannedTypes.empty());
    }

    /** Builds the cache over explicit inputs (tests, no-CDI usage). */
    public GrimmModelCache(GrimmConfig config, ScannedTypes scannedTypes) {
        this.config = Objects.requireNonNullElse(config, GrimmConfig.defaults());
        this.scannedTypes = Objects.requireNonNullElse(scannedTypes, ScannedTypes.empty());
    }

    /** Default constructor for tests / no-CDI usage. */
    public GrimmModelCache() {
        this(GrimmConfig.defaults(), ScannedTypes.empty());
    }

    @PostConstruct
    void init() {
        initialize(config, scannedTypes.classes());
    }

    /**
     * Runs the pipeline and stores the result. Idempotent: subsequent calls are no-ops
     * once the document is set.
     */
    public void initialize(GrimmConfig config, Collection<Class<?>> annotatedTypes) {
        if (document != null) {
            return;
        }
        lock.lock();
        try {
            if (document != null) {
                return;
            }
            document = modelBuilder.build(config, annotatedTypes != null ? annotatedTypes : List.of());
        } finally {
            lock.unlock();
        }
    }

    /** Manually inject a pre-built document (test path). */
    public void setDocument(OpenAPI document) {
        lock.lock();
        try {
            this.document = document;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the cached document; lazily builds an empty skeleton if uninitialized. */
    public OpenAPI getDocument() {
        OpenAPI snapshot = document;
        if (snapshot != null) {
            return snapshot;
        }
        lock.lock();
        try {
            if (document == null) {
                document = OASFactory.createObject(OpenAPI.class).openapi("3.1.0");
            }
            return document;
        } finally {
            lock.unlock();
        }
    }
}

