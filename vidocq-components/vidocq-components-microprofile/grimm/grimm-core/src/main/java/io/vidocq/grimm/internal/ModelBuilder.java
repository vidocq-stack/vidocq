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
package io.vidocq.grimm.internal;

import io.vidocq.grimm.internal.config.ConfigApplier;
import io.vidocq.grimm.internal.config.GrimmConfig;
import io.vidocq.grimm.internal.invoker.FilterInvoker;
import io.vidocq.grimm.internal.invoker.ModelReaderInvoker;
import io.vidocq.grimm.internal.merger.AnnotationSource;
import io.vidocq.grimm.internal.merger.ModelMerger;
import io.vidocq.grimm.internal.merger.ModelSource;
import io.vidocq.grimm.internal.merger.ReaderSource;
import io.vidocq.grimm.internal.merger.StaticFileSource;
import io.vidocq.grimm.internal.reader.StaticFileReader;
import io.vidocq.grimm.internal.scanner.AnnotationScanner;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.info.Info;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the full MicroProfile OpenAPI 4.2 model build pipeline (spec §4.4).
 *
 * <p>Six steps, executed once at startup, in the order of the spec "Processing rules":</p>
 * <ol>
 *   <li>Invoke {@link org.eclipse.microprofile.openapi.OASModelReader} if configured
 *       ({@link ModelReaderInvoker})</li>
 *   <li>Read static file ({@link StaticFileReader})</li>
 *   <li>Scan annotated classes ({@link AnnotationScanner})</li>
 *   <li>Merge the three sources with spec priority annotations &gt; static file &gt; reader
 *       ({@link ModelMerger})</li>
 *   <li>Apply {@code mp.openapi.servers} / {@code mp.openapi.schema.<FQCN>}
 *       ({@link ConfigApplier})</li>
 *   <li>Apply {@link org.eclipse.microprofile.openapi.OASFilter} if configured
 *       ({@link FilterInvoker})</li>
 * </ol>
 *
 * <p>Pure Java — no CDI dependency. Consumed by the {@code grimm-cdi-vauban}
 * extension which calls {@link #build(GrimmConfig, Collection)} at container startup.</p>
 */
public final class ModelBuilder {

    private final StaticFileReader staticFileReader = new StaticFileReader();
    private final ModelReaderInvoker modelReaderInvoker = new ModelReaderInvoker();
    private final ModelMerger merger = new ModelMerger();
    private final FilterInvoker filterInvoker = new FilterInvoker();

    /**
     * Runs the full pipeline.
     *
     * @param config         immutable configuration snapshot (must not be {@code null})
     * @param annotatedTypes JAX-RS resource / model classes to scan (may be empty)
     * @return the merged, filtered {@link OpenAPI} document
     */
    public OpenAPI build(GrimmConfig config, Collection<Class<?>> annotatedTypes) {
        if (config == null) config = GrimmConfig.defaults();
        if (annotatedTypes == null) annotatedTypes = List.of();

        // Honor mp.openapi.scan.beanvalidation across the whole build.
        io.vidocq.grimm.internal.schema.BeanValidationMapper.setEnabled(config.scan().scanBeanValidation());

        List<ModelSource> sources = new ArrayList<>(3);

        // Step 1 — OASModelReader: the starting model (spec "Processing rules", step 2)
        OpenAPI readerModel = modelReaderInvoker.invokeModelReader(config.filter());
        if (readerModel != null) {
            sources.add(new ReaderSource(readerModel));
        }

        // Step 2 — static file, overriding the reader's conflicting elements (step 3)
        Optional<OpenAPI> staticModel = staticFileReader.readOpenAPI(annotatedTypes);
        staticModel.ifPresent(m -> sources.add(new StaticFileSource(m)));

        // Step 3 — annotation source: compile-time $$GrimmModel contributions first
        // (CG-06, APT-first rule), reflective scan as documented fallback for the rest.
        java.util.List<Class<?>> toScan = new ArrayList<>(annotatedTypes.size());
        java.util.List<OpenAPI> fragments = new ArrayList<>();
        var fragmentReader = new io.vidocq.grimm.internal.serialization.JsonDeserializer();
        for (Class<?> annotatedType : annotatedTypes) {
            var contribution = ContributionRegistry.resolve(annotatedType);
            if (contribution != null) {
                fragments.add(fragmentReader.deserialize(contribution.openApiJson()));
            } else {
                ContributionRegistry.noteScanFallback();
                toScan.add(annotatedType);
            }
        }

        AnnotationScanner scanner = new AnnotationScanner(config.scan());

        // Register schema overrides before scan so generator can resolve scalar refs (e.g. Instant).
        ConfigApplier.applySchemaOverrides(scanner.schemaRegistry(), config);

        OpenAPI annotationModel = scanner.scanClasses(toScan);
        for (OpenAPI fragment : fragments) {
            io.vidocq.grimm.internal.merger.FragmentMerger.mergeInto(annotationModel, fragment);
        }
        sources.add(new AnnotationSource(annotationModel));

        // Step 4 — merge with spec priority (§4.4)
        OpenAPI merged = merger.merge(sources);
        if (merged.getOpenapi() == null) {
            merged.setOpenapi("3.1.0");
        }
        ensureDefaultInfo(merged);

        // Step 5 — apply config overrides (mp.openapi.servers, mp.openapi.schema.<FQCN>)
        ConfigApplier.applyServers(merged, config);
        scanner.schemaRegistry().applyTo(merged);

        // Step 6 — OASFilter (§4.3)
        OpenAPI filtered = filterInvoker.applyFilter(merged, config.filter());

        // Defensive: if filterInvoker returned null (model was null), fall back to empty model.
        return filtered != null ? filtered : OASFactory.createObject(OpenAPI.class).openapi("3.1.0");
    }

    private void ensureDefaultInfo(OpenAPI model) {
        Info info = model.getInfo();
        if (info == null) {
            info = OASFactory.createObject(Info.class);
            model.setInfo(info);
        }
        if (info.getTitle() == null || info.getTitle().isBlank()) {
            info.setTitle("Generated API");
        }
        if (info.getVersion() == null || info.getVersion().isBlank()) {
            info.setVersion("1.0");
        }
    }
}


