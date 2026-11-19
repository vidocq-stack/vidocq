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
package io.vidocq.peano.core.internal.evaluation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceConfigurationError;
import io.vidocq.peano.spi.ConversionResult;
import io.vidocq.peano.spi.TypeConversionProvider;
import jakarta.el.ELException;

final class RegisteredConversions {
    private final List<TypeConversionProvider> providers;
    RegisteredConversions(Collection<? extends TypeConversionProvider> providers) {
        this.providers=providers.stream().map(TypeConversionProvider.class::cast)
                .sorted(Comparator.comparingInt(TypeConversionProvider::priority).reversed()
                        .thenComparing(provider->provider.getClass().getName())).toList();
    }
    static ConversionResult discover(Object value,Class<?> target) {
        try {
            var providers=ServiceLoader.load(TypeConversionProvider.class).stream().map(ServiceLoader.Provider::get).toList();
            return new RegisteredConversions(providers).convert(value,target);
        } catch (ServiceConfigurationError failure) {
            throw new ELException("Cannot load type conversion providers",failure);
        }
    }
    ConversionResult convert(Object value,Class<?> target) {
        for (var provider:providers) {
            ConversionResult result=provider.convert(value,target);
            if (result==null) throw new ELException("Conversion provider returned null: "+provider.getClass().getName());
            if (result.handled()) {
                if (result.value()!=null && !target.isInstance(result.value()))
                    throw new ELException("Conversion provider returned an invalid value for "+target.getName());
                return result;
            }
        }
        return ConversionResult.unhandled();
    }
}

