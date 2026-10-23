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
package io.vidocq.runtime.extensions.jakartaee.web.vaadin;

import com.vaadin.flow.di.AbstractLookupInitializer;
import com.vaadin.flow.di.Lookup;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lookup Vaadin gets from {@link ModuleLookupInitializer}: the services Vaadin registered come first, and a type
 * it has none for is loaded by {@code ServiceLoader} from this module, which declares the use itself. The providers
 * of the test types are listed in this test's {@code META-INF/services}.
 */
class ModuleLookupInitializerTest {

    private static final BiFunction<Class<?>, Class<?>, Object> NEW_INSTANCE = (type, implementation) -> {
        try {
            return implementation.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    };

    @Test
    void vaadinUsesTheInitializerInPlaceOfItsOwn() {
        // Vaadin picks the one AbstractLookupInitializer its @HandlesTypes finds that is not its default.
        assertTrue(AbstractLookupInitializer.class.isAssignableFrom(ModuleLookupInitializer.class));
    }

    @Test
    void aRegisteredServiceComesFirst() {
        Lookup lookup = lookup(Map.of(Greeting.class, List.of(Registered.class)));

        assertInstanceOf(Registered.class, lookup.lookup(Greeting.class),
                "a registered service wins over the ServiceLoader providers");
    }

    @Test
    void aTypeWithoutRegisteredServiceIsLoadedFromThisModule() {
        assertInstanceOf(OnlyShell.class, lookup(Map.of()).lookup(Shell.class));
    }

    @Test
    void severalProvidersForOneServiceAreAnError() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> lookup(Map.of()).lookup(Greeting.class));

        assertTrue(error.getMessage().contains(Greeting.class.getName()), error.getMessage());
    }

    @Test
    void noProviderIsNull() {
        assertNull(lookup(Map.of()).lookup(Unprovided.class));
    }

    @Test
    void lookupAllHasTheRegisteredServicesThenTheOthersLoaded() {
        Collection<Greeting> all = lookup(Map.of(Greeting.class, List.of(Hello.class))).lookupAll(Greeting.class);

        assertEquals(List.of(Hello.class, Bonjour.class), all.stream().map(Object::getClass).toList(),
                "Hello, registered and loaded, appears once");
    }

    private static Lookup lookup(Map<Class<?>, Collection<Class<?>>> services) {
        return new ModuleLookupInitializer.ModuleLookup(services, NEW_INSTANCE);
    }

    public interface Greeting {
    }

    public static final class Registered implements Greeting {
    }

    public static final class Hello implements Greeting {
    }

    public static final class Bonjour implements Greeting {
    }

    /** A class Vaadin asks the lookup for, as it does for the application's app shell. */
    public interface Shell {
    }

    public static final class OnlyShell implements Shell {
    }

    public interface Unprovided {
    }
}
