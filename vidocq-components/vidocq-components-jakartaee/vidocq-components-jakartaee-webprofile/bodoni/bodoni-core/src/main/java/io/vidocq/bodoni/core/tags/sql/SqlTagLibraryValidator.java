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

package io.vidocq.bodoni.core.tags.sql;

import jakarta.servlet.jsp.tagext.PageData;
import jakarta.servlet.jsp.tagext.TagLibraryValidator;
import jakarta.servlet.jsp.tagext.ValidationMessage;

import io.vidocq.bodoni.core.internal.PageConstraints;
import io.vidocq.bodoni.core.internal.PageConstraints.Library;

/**
 * The validator the {@code sql:} descriptors name: reports at translation time the constraints of
 * Tags 3.0 §2.7 and §10.3-§10.5 on the XML view of a page.
 *
 * <p>Public, final and stateless, with a public no-argument constructor: a Pages container
 * instantiates it by class name, and pages precompiled by Ibarra call {@code new} on it from the
 * application module. It takes no initialization parameter.
 */
public final class SqlTagLibraryValidator extends TagLibraryValidator {

    /** Creates the validator. */
    public SqlTagLibraryValidator() {
        // Stateless: every call to validate walks the page on its own.
    }

    @Override
    public ValidationMessage[] validate(String prefix, String uri, PageData page) {
        return PageConstraints.validate(Library.SQL, uri, page);
    }
}
