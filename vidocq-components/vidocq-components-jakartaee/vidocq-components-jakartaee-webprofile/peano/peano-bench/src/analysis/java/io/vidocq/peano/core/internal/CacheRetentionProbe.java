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

import java.lang.management.ManagementFactory;

/** Isolated, diagnostic post-GC retention check; never part of shipped artifacts. */
public final class CacheRetentionProbe {
    private static volatile ParsedExpressionCache retained;
    private CacheRetentionProbe() {}

    private static void populate(ParsedExpressionCache cache) {
        for (int i=0;i<4096;i++) cache.parse("\u0024{a+"+i+"}");
    }

    private static long used() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    public static void main(String[] arguments) {
        int slots=Integer.parseInt(arguments[0]);
        populate(new ParsedExpressionCache(128,4096,16*1024L));
        populate(new ParsedExpressionCache(0,4096,16*1024L));
        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        retained=new ParsedExpressionCache(slots,4096,16*1024L);
        long before=used();
        populate(retained);
        long after=used();
        System.out.println("slots="+slots+" entries="+retained.retainedEntries()
                +" estimatedBytes="+retained.retainedEstimatedBytes()+" retainedHeapDelta="+(after-before));
    }
}
