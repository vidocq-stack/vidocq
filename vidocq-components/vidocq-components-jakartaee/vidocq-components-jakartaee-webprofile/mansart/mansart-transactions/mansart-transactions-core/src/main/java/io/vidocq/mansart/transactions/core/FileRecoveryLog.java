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
import java.util.Arrays;
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

    /** Completed transactions after which the journal is rewritten without them. */
    private static final int COMPACT_EVERY = 1024;

    private final Path file;
    private final int compactEvery;
    private java.nio.channels.FileChannel channel;
    private boolean closed;
    private int completedSinceCompaction;

    public FileRecoveryLog(Path file) throws IOException {
        this(file, COMPACT_EVERY);
    }

    /**
     * @param compactEvery completed transactions after which the journal is rewritten with the transactions still
     *                     in doubt only (MANSART-062); it is also rewritten when opened
     */
    public FileRecoveryLog(Path file, int compactEvery) throws IOException {
        this.file = file;
        this.compactEvery = compactEvery;
        Files.createDirectories(file.toAbsolutePath().getParent());
        compact();
        this.channel = java.nio.channels.FileChannel.open(file, APPEND_OPTS);
    }

    /**
     * Rewrites the journal with the last record of each transaction that has no {@code COMPLETED} one: a temporary
     * file, forced to disk, then moved over the journal in one step, so a crash leaves the old journal or the new.
     */
    private void compact() throws IOException {
        if (!Files.exists(file)) return;
        var inDoubt = new java.util.LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int sep = line.indexOf(SEP);
            if (sep < 0 || line.split("\\|").length != 4) continue;      // corrupt or truncated line
            String transaction = line.substring(sep);
            if (line.startsWith(Type.COMPLETED.name() + SEP)) {
                inDoubt.remove(transaction);
            } else {
                inDoubt.put(transaction, line);
            }
        }
        if (channel != null) {
            channel.close();
            channel = null;
        }
        Path rewritten = file.resolveSibling(file.getFileName() + ".compact");
        var content = new StringBuilder();
        inDoubt.values().forEach(line -> content.append(line).append('\n'));
        try (var out = java.nio.channels.FileChannel.open(rewritten, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write(java.nio.ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8)));
            out.force(true);
        }
        Files.move(rewritten, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        completedSinceCompaction = 0;
    }

    @Override
    public synchronized void append(Record entry) throws IOException {
        reopen();
        String line = entry.type().name() + SEP
                + Integer.toHexString(entry.xid().getFormatId()).toUpperCase() + SEP
                + HEX.formatHex(entry.xid().getGlobalTransactionId()) + SEP
                + HEX.formatHex(entry.xid().getBranchQualifier()) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        channel.write(java.nio.ByteBuffer.wrap(bytes));
        // DSYNC open option already forces sync on every write, force(true) is belt-and-suspenders
        // — kept explicit so a future migration to plain WRITE doesn't silently lose durability.
        channel.force(true);
        if (entry.type() == Type.COMPLETED && ++completedSinceCompaction >= compactEvery) {
            compact();
            reopen();
        }
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
    public synchronized void release() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    /** Opens the file at its path again; a closed journal stays closed. */
    @Override
    public synchronized void reopen() throws IOException {
        if (closed) {
            throw new IOException("The recovery log is closed: " + file);
        }
        if (channel == null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            channel = java.nio.channels.FileChannel.open(file, APPEND_OPTS);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        release();
    }

    /** Lightweight {@link Xid} constructed by {@link #scan()} — only the bytes matter for
     *  comparison, so {@code MansartXid} would be overkill (it pins the format id). */
    private record ScannedXid(int format, byte[] gtrid, byte[] bqual) implements Xid {
        @Override public int    getFormatId()             { return format; }
        @Override public byte[] getGlobalTransactionId()  { return gtrid.clone(); }
        @Override public byte[] getBranchQualifier()      { return bqual.clone(); }

        /** By content (MANSART-010): the record default would compare the arrays by identity. */
        @Override
        public boolean equals(Object o) {
            return o instanceof ScannedXid(var otherFormat, var otherGtrid, var otherBqual)
                    && format == otherFormat
                    && Arrays.equals(gtrid, otherGtrid)
                    && Arrays.equals(bqual, otherBqual);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * format + Arrays.hashCode(gtrid)) + Arrays.hashCode(bqual);
        }

        @Override
        public String toString() {
            return "Xid{format=" + Integer.toHexString(format)
                    + ", gtrid=" + HEX.formatHex(gtrid)
                    + ", bqual=" + HEX.formatHex(bqual) + "}";
        }
    }
}
