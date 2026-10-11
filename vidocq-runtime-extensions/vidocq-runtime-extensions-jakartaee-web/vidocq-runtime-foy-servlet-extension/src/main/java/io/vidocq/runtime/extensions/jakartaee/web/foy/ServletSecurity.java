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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.foy.spi.security.IdentityStore;
import io.vidocq.runtime.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * The user base and the transport rules of Servlet security (Servlet 6.1 chapter 13), handed to Foy.
 * The user base is the application's {@link IdentityStore} bean when it has one, else the users of
 * {@code vidocq.servlet.users.<name>.password} and {@code .roles}, meant for development: Foy keeps
 * only a salted digest of each password, but the configuration holds it in clear.
 *
 * @param users                  the configured users, by name
 * @param confidentialPort       {@code vidocq.servlet.confidential-port}: the HTTPS port a
 *                               CONFIDENTIAL constraint redirects to; empty: such a request gets 403
 * @param rotateSessionIdOnLogin {@code vidocq.servlet.rotate-session-id-on-login}; empty: Foy's
 *                               default, on
 */
record ServletSecurity(List<User> users, OptionalInt confidentialPort, Optional<Boolean> rotateSessionIdOnLogin) {

    static final String USERS = "vidocq.servlet.users.";
    static final String CONFIDENTIAL_PORT = "vidocq.servlet.confidential-port";
    static final String ROTATE_SESSION_ID = "vidocq.servlet.rotate-session-id-on-login";

    /** A configured user; {@code password} is wiped once handed to Foy. */
    record User(String name, char[] password, List<String> roles) {}

    static ServletSecurity from(VidocqConfig config) {
        Set<String> names = new TreeSet<>();
        for (String key : config.getPropertyNames()) {
            if (!key.startsWith(USERS)) continue;
            String rest = key.substring(USERS.length());
            int dot = rest.lastIndexOf('.');
            String property = dot < 0 ? "" : rest.substring(dot + 1);
            if (dot <= 0 || !(property.equals("password") || property.equals("roles"))) {
                throw new IllegalArgumentException("unknown key " + key + ": a user is configured with "
                        + USERS + "<name>.password and " + USERS + "<name>.roles");
            }
            names.add(rest.substring(0, dot));
        }
        List<User> users = new ArrayList<>();
        for (String name : names) {
            String passwordKey = USERS + name + ".password";
            String password = config.getValue(passwordKey).orElseThrow(() -> new IllegalArgumentException(
                    "user '" + name + "' has no password: set " + passwordKey));
            List<String> roles = config.getValue(USERS + name + ".roles")
                    .map(r -> Arrays.stream(r.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())
                    .orElse(List.of());
            users.add(new User(name, password.toCharArray(), roles));
        }
        OptionalInt port = config.getValue(CONFIDENTIAL_PORT).map(v -> OptionalInt.of(port(v)))
                .orElse(OptionalInt.empty());
        Optional<Boolean> rotate = config.getValue(ROTATE_SESSION_ID).map(ServletSecurity::flag);
        return new ServletSecurity(List.copyOf(users), port, rotate);
    }

    /**
     * Configures {@code builder}.
     *
     * @param bean the application's {@link IdentityStore} bean, if any
     * @throws IllegalStateException when there is both a bean and configured users
     */
    void applyTo(FoyChappeBoot.Builder builder, Optional<IdentityStore> bean) {
        if (bean.isPresent() && !users.isEmpty()) {
            throw new IllegalStateException("the application has an IdentityStore bean and configures "
                    + USERS + "*: keep one user base");
        }
        bean.ifPresent(builder::identityStore);
        for (User user : users) {
            builder.user(user.name(), user.password(), user.roles().toArray(String[]::new));
            Arrays.fill(user.password(), '\0');
        }
        confidentialPort.ifPresent(p -> {
            try {
                builder.confidentialPort(p);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(CONFIDENTIAL_PORT + ": " + e.getMessage(), e);
            }
        });
        rotateSessionIdOnLogin.ifPresent(builder::rotateSessionIdOnLogin);
    }

    /** The application's {@link IdentityStore} bean; empty without a bean manager or a bean. */
    static Optional<IdentityStore> identityStoreBean(BeanManager beanManager) {
        if (beanManager == null) return Optional.empty();
        Set<Bean<?>> beans = beanManager.getBeans(IdentityStore.class);
        if (beans.isEmpty()) return Optional.empty();
        Bean<?> bean = beanManager.resolve(beans);
        return Optional.of((IdentityStore) beanManager.getReference(bean, IdentityStore.class,
                beanManager.createCreationalContext(bean)));
    }

    private static int port(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(CONFIDENTIAL_PORT + " must be a port number, got '" + value + "'", e);
        }
    }

    private static boolean flag(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(ROTATE_SESSION_ID + " must be true or false, got '" + value + "'");
        };
    }
}
