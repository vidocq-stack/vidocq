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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;

/**
 * Which methods write (spec §3), so that they ask first and run in a transaction: {@code save}, {@code saveAll},
 * a name starting with {@code insert}, {@code update} or {@code delete} (a derived {@code delete…By…} included),
 * {@code @Insert}, {@code @Update}, {@code @Delete}, {@code @Save}, and a {@code @Query} whose text starts, after
 * blanks, with {@code UPDATE} or {@code DELETE}, in any case.
 */
final class WriteKinds {

    private WriteKinds() {}

    /**
     * The {@code @Transactional} kinds that do not join the console's transaction: the method commits in a transaction
     * of its own, or runs outside any, so the console's rollback would not undo it.
     */
    private static final Set<String> ESCAPING = Set.of("REQUIRES_NEW", "NOT_SUPPORTED", "NEVER");

    /** The annotation {@code jakarta.transaction.Transactional}, read by name: the API is optional here. */
    private static final String TRANSACTIONAL = "jakarta.transaction.Transactional";

    static boolean isWrite(Method method) {
        if (method.isDefault()) {
            // Mansart runs a default method as it is written: what it does is unknown, so it asks first
            return true;
        }
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

    /**
     * Whether {@code method} of {@code repository} runs outside the console's transaction: a {@code @Transactional}
     * on the method, else on the repository, else on the interface that declares the method, of kind
     * {@code REQUIRES_NEW}, {@code NOT_SUPPORTED} or {@code NEVER}. Such a write can only be committed.
     */
    static boolean escapesTransaction(Method method, Class<?> repository) {
        for (AnnotatedElement element : new AnnotatedElement[] {method, repository, method.getDeclaringClass()}) {
            for (Annotation annotation : element.getAnnotations()) {
                if (annotation.annotationType().getName().equals(TRANSACTIONAL)) {
                    return ESCAPING.contains(kind(annotation));
                }
            }
        }
        return false;
    }

    /** The {@code value()} of a {@code @Transactional}, by name; {@code REQUIRED} when it cannot be read. */
    private static String kind(Annotation transactional) {
        try {
            Object value = transactional.annotationType().getMethod("value").invoke(transactional);
            return value instanceof Enum<?> e ? e.name() : "REQUIRED";
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return "REQUIRED";
        }
    }
}
