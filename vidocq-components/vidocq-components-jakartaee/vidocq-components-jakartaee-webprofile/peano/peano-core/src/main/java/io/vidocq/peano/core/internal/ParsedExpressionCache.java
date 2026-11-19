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
package io.vidocq.peano.core.internal;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Factory-owned, direct-mapped syntax reuse; never stores bound application state. */
final class ParsedExpressionCache {
    private record Entry(String source,ExpressionAnalysis analysis) {}
    private final ConcurrentHashMap<Integer,Entry> slots=new ConcurrentHashMap<>();
    private final int slotCount;
    private final int maxSourceChars;
    private final long maxEntryBytes;

    ParsedExpressionCache() { this(128,4096,16*1024L); }

    ParsedExpressionCache(int slotCount,int maxSourceChars,long maxEntryBytes) {
        if (slotCount<0 || slotCount>128 || (slotCount!=0 && (slotCount&(slotCount-1))!=0)
                || maxSourceChars<0 || maxEntryBytes<0) throw new IllegalArgumentException("Invalid syntax cache bounds");
        this.slotCount=slotCount; this.maxSourceChars=maxSourceChars; this.maxEntryBytes=maxEntryBytes;
    }

    ExpressionAnalysis parse(String source) {
        Objects.requireNonNull(source,"source");
        if (slotCount==0 || source.length()>maxSourceChars) return ExpressionAnalysis.parse(source);
        int hash=source.hashCode();
        int slot=(hash^(hash>>>16))&(slotCount-1);
        Entry existing=slots.get(slot);
        if (existing!=null && existing.source().equals(source)) return existing.analysis().owned();
        ExpressionAnalysis analysis=ExpressionAnalysis.parse(source);
        if (analysis.estimatedBytes()<=maxEntryBytes) slots.put(slot,new Entry(source,analysis));
        // A concurrent collision may replace the slot; return this caller's analyzed source.
        return analysis;
    }

    int retainedEntries() { return slots.size(); }
    long retainedEstimatedBytes() {
        long bytes=0;
        for (Entry entry:slots.values()) bytes+=entry.analysis().estimatedBytes();
        return bytes;
    }
}
