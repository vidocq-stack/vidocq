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
package io.vidocq.mansart.transactions.core;

import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Append-only text journal — one line per {@link Record}, fsync'd to disk after every write.
 * Format keeps a pure-ASCII line so it stays grep-able and trivially diffable :
 * <pre>
 *   {type}|{format_id_hex}|{gtrid_hex}|{bqual_hex}
 *   PREPARED|4D414E53|0000000000000001|00
 *   COMMITTING|4D414E53|0000000000000001|00
 *   COMPLETED|4D414E53|0000000000000001|00
 * </pre>
 *
 * <p>Trade-off : a binary format would be smaller and slightly faster, but recovery debugging
 * is the dominant concern here — being able to {@code cat tx-recovery.log} on a hung container
 * is worth more than 50% size win in a regime where typical apps generate maybe 10K records/day.
 *
 * <p>Concurrency : {@link #append(Record)} is {@code synchronized} ; the sequential file write
 * is the bottleneck anyway. Multi-threaded TM commits serialise on the journal — that is the
 * intended behaviour to keep the on-disk order monotonic.
 */
public final class FileRecoveryLog implements RecoveryLog {

    private static final String SEP = "|";
    private static final HexFormat HEX = HexFormat.of();
    private static final OpenOption[] APPEND_OPTS = {
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
            StandardOpenOption.DSYNC };

    private final Path file;
    private final java.nio.channels.FileChannel channel;

    public FileRecoveryLog(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        this.channel = java.nio.channels.FileChannel.open(file, APPEND_OPTS);
    }

    @Override
    public synchronized void append(Record record) throws IOException {
        String line = record.type().name() + SEP
                + Integer.toHexString(record.xid().getFormatId()).toUpperCase() + SEP
                + HEX.formatHex(record.xid().getGlobalTransactionId()) + SEP
                + HEX.formatHex(record.xid().getBranchQualifier()) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        channel.write(java.nio.ByteBuffer.wrap(bytes));
        // DSYNC open option already forces sync on every write, force(true) is belt-and-suspenders
        // — kept explicit so a future migration to plain WRITE doesn't silently lose durability.
        channel.force(true);
    }

    @Override
    public List<Record> scan() throws IOException {
        if (!Files.exists(file)) return List.of();
        var out = new ArrayList<Record>();
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) return;
                String[] parts = line.split("\\|");
                if (parts.length != 4) return;     // skip corrupt / truncated tail line
                try {
                    Type type = Type.valueOf(parts[0]);
                    int formatId = Integer.parseUnsignedInt(parts[1], 16);
                    byte[] gtrid = HEX.parseHex(parts[2]);
                    byte[] bqual = HEX.parseHex(parts[3]);
                    out.add(new Record(type, new ScannedXid(formatId, gtrid, bqual)));
                } catch (IllegalArgumentException _) {
                    // Corruption in any field (unknown enum, malformed hex, odd length, …) →
                    // silently drop the line. The records before it stay valid because the
                    // log is append-only and write-fsynced — corruption only happens at the tail.
                }
            });
        }
        return out;
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    /** Lightweight {@link Xid} constructed by {@link #scan()} — only the bytes matter for
     *  comparison, so {@code MansartXid} would be overkill (it pins the format id). */
    private record ScannedXid(int format, byte[] gtrid, byte[] bqual) implements Xid {
        @Override public int    getFormatId()             { return format; }
        @Override public byte[] getGlobalTransactionId()  { return gtrid.clone(); }
        @Override public byte[] getBranchQualifier()      { return bqual.clone(); }
    }
}
