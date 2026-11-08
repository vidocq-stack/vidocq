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
package io.vidocq.archrules;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One layer of a brick for {@link VidocqArchRules#layers}: a name, the packages it holds, and who may reach it.
 * By default a layer is unconstrained; the methods narrow it.
 */
public final class Layer {

    private final String name;
    private String[] packages = new String[0];
    private List<String> accessibleOnlyBy;
    private boolean accessesNoLayer;

    private Layer(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** A layer called {@code name}. */
    public static Layer named(String name) {
        return new Layer(name);
    }

    /** The packages the layer holds, in ArchUnit's {@code ..} notation. */
    public Layer in(String... packages) {
        this.packages = packages.clone();
        return this;
    }

    /** Only the layers named here may access this layer; none when the list is empty. */
    public Layer accessibleOnlyBy(String... layers) {
        this.accessibleOnlyBy = Arrays.asList(layers.clone());
        return this;
    }

    /** No layer accesses this one. */
    public Layer accessibleByNone() {
        return accessibleOnlyBy();
    }

    /** This layer accesses no other layer. */
    public Layer accessesNoLayer() {
        this.accessesNoLayer = true;
        return this;
    }

    String name() {
        return name;
    }

    String[] packages() {
        return packages.clone();
    }

    List<String> accessibleOnlyByLayers() {
        return accessibleOnlyBy;
    }

    boolean accessesNoLayerAtAll() {
        return accessesNoLayer;
    }
}
