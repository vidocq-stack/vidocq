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

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;

import java.util.List;

/**
 * Regression repository for MANSART-002. The 4-condition {@code And} chain below must parse into four
 * predicates (it previously failed: only the first {@code And} was split, the rest bundled into an
 * unresolvable token → silent {@code UnsupportedOperationException} stub). The 2-condition method is
 * the control that always worked.
 */
@Repository
public interface LabSeatRepository extends BasicRepository<LabSeat, Long> {

    /** Control: two-condition And (always supported). */
    List<LabSeat> findByRoomIdAndReleased(String roomId, boolean released);

    /** The regression: four-condition And chain (String, int, int, boolean). */
    List<LabSeat> findByRoomIdAndSeatRowAndSeatBlockIndexAndReleased(
            String roomId, int seatRow, int seatBlockIndex, boolean released);
}
