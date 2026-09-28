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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Which methods write (spec §3), so that they ask first and run in a transaction: {@code save}, {@code saveAll},
 * a name starting with {@code insert}, {@code update} or {@code delete} (a derived {@code delete…By…} included),
 * {@code @Insert}, {@code @Update}, {@code @Delete}, {@code @Save}, and a {@code @Query} whose text starts, after
 * blanks, with {@code UPDATE} or {@code DELETE}, in any case.
 */
final class WriteKinds {

    private WriteKinds() {}

    static boolean isWrite(Method method) {
        String name = method.getName();
        if (name.equals("save") || name.equals("saveAll") || name.startsWith("insert") || name.startsWith("update")
                || name.startsWith("delete")) {
            return true;
        }
        if (method.isAnnotationPresent(Insert.class) || method.isAnnotationPresent(Update.class)
                || method.isAnnotationPresent(Delete.class) || method.isAnnotationPresent(Save.class)) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
            return false;
        }
        String text = query.value().stripLeading().toUpperCase(Locale.ROOT);
        return text.startsWith("UPDATE") || text.startsWith("DELETE");
    }
}
