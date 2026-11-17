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
package io.vidocq.mansart.data.tests;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

import java.util.List;

/**
 * A repository on the named data store {@code "auditds"} whose built-in supertype is two levels up:
 * {@code BasicRepository<ArchivedNote, Long>} comes through the type variables of {@code CrudRepository}. Its other
 * super-interface has a type argument that is not a class (MANSART-068).
 */
@Repository(dataStore = "auditds")
public interface RoutedNoteArchive extends CrudRepository<ArchivedNote, Long>, Labelled<List<String>> {
}
