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
package io.vidocq.mansart.pool.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@link SessionAttribute session attributes} of one physical connection: the value the driver opened it with,
 * and the value the current borrower set (MANSART-047). The driver's value is read the first time a borrower is
 * about to change the attribute, so a client that changes nothing costs no call; the release puts back only the
 * attributes a borrower left on another value.
 *
 * <p>One borrower at a time uses an entry, and the pool's hand-over (its semaphore and idle deque) orders a release
 * before the next borrow: the fields need no synchronisation.
 */
final class SessionState {

    private final Map<SessionAttribute, Object> initial = new EnumMap<>(SessionAttribute.class);
    private final Map<SessionAttribute, Object> current = new EnumMap<>(SessionAttribute.class);
    /** The attributes a borrower set since the last restore. */
    private final Set<SessionAttribute> changed = EnumSet.noneOf(SessionAttribute.class);
    /** An initial value could not be read: the connection cannot be put back as the driver opened it. */
    private boolean lost;

    /** Called before a borrower sets {@code attribute} to {@code value} on {@code driver}. */
    void changing(Connection driver, SessionAttribute attribute, Object value) {
        // The initial value is known once read: a null value (no schema) is a value, hence containsKey.
        if (!initial.containsKey(attribute)) {
            try {
                initial.put(attribute, attribute.read(driver));
            } catch (SQLException _) {
                lost = true;    // the borrower's change still goes through; the release discards the connection
            }
        }
        current.put(attribute, value);
        changed.add(attribute);
    }

    /**
     * Puts back, on the driver's connection, every attribute a borrower left on another value than the driver's.
     *
     * @throws SQLException when an initial value was never read or the driver refuses it: the pool then closes the
     *                      connection instead of lending it again
     */
    void restore(Connection driver) throws SQLException {
        if (changed.isEmpty()) return;
        if (lost) {
            throw new SQLException("A session attribute changed by a borrower could not be read first: the pool"
                    + " cannot put the connection back as the driver opened it");
        }
        for (SessionAttribute attribute : changed) {
            Object initialValue = initial.get(attribute);
            if (!Objects.equals(current.get(attribute), initialValue)) {
                attribute.write(driver, initialValue);
            }
        }
        changed.clear();
    }
}
