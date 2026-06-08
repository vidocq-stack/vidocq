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
package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.invoke.Invoker;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

/**
 * Synthetic-bean creator used by {@link MansartDataExtension}. Each generated {@code XxxRepository}
 * is exposed as an {@code @ApplicationScoped} synthetic bean whose {@code create} delegates here.
 *
 * <p>The {@code implClass} (a {@link Class}) is passed via {@link Parameters}; we look it up,
 * fetch the shared {@link RepositoryRuntime} from the container, and instantiate via the
 * generated {@code (RepositoryRuntime)} constructor.
 */
public final class MansartRepoCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        Class<?> implClass = params.get("implClass", Class.class);
        Class<?> itfClass  = params.get("itfClass",  Class.class);
        if (implClass == null) {
            throw new MansartDataException("MansartRepoCreator: missing 'implClass' parameter");
        }
        // M9 — pick the RepositoryRuntime keyed by the @Repository(dataStore) value.
        // Falls back to the @Default RepositoryRuntime when dataStore is empty.
        RepositoryRuntime runtime = DataStoreResolver.resolve(lookup, itfClass);
        try {
            var ctor = implClass.getDeclaredConstructor(RepositoryRuntime.class);
            ctor.setAccessible(true);
            return ctor.newInstance(runtime);
        } catch (ReflectiveOperationException e) {
            throw new MansartDataException("Failed to instantiate " + implClass.getName(), e);
        }
    }
}
