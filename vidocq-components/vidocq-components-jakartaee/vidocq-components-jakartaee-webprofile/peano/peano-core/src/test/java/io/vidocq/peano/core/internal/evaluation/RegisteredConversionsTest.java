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
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import io.vidocq.peano.spi.*;
import jakarta.el.ELException;
import org.junit.jupiter.api.Test;
class RegisteredConversionsTest {
    static class ZProvider implements TypeConversionProvider {
        public ConversionResult convert(Object value,Class<?> type) { return ConversionResult.handled("z"); }
    }
    static class AProvider implements TypeConversionProvider {
        public ConversionResult convert(Object value,Class<?> type) { return ConversionResult.handled("a"); }
    }
    @Test void ordersProvidersWithoutDependingOnRegistrationOrder() {
        assertEquals("a",new RegisteredConversions(List.of(new ZProvider(),new AProvider())).convert("x",String.class).value());
        var priority=new AProvider() {
            @Override public int priority(){ return 7; }
            @Override public ConversionResult convert(Object value,Class<?> type){ return ConversionResult.handled("priority"); }
        };
        assertEquals("priority",new RegisteredConversions(List.of(new ZProvider(),priority)).convert("x",String.class).value());
    }
    @Test void distinguishesNullFromAnUnhandledConversion() {
        TypeConversionProvider absent=(value,type)->ConversionResult.unhandled();
        TypeConversionProvider nullable=(value,type)->ConversionResult.handled(null);
        var result=new RegisteredConversions(List.of(absent,nullable)).convert("",Object.class);
        assertTrue(result.handled());
        assertNull(result.value());
    }
    @Test void rejectsProviderContractViolations() {
        assertThrows(IllegalArgumentException.class,()->new ConversionResult(false,"invalid"));
        var nullResult=new RegisteredConversions(List.of((value,type)->null));
        assertThrows(ELException.class,()->nullResult.convert("x",Object.class));
        var wrongType=new RegisteredConversions(List.of((value,type)->ConversionResult.handled(7)));
        assertThrows(ELException.class,()->wrongType.convert("x",String.class));
    }
}

