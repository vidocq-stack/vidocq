package io.vidocq.mansart.transactions.core;

import javax.transaction.xa.Xid;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Minimal {@link Xid} implementation — an opaque branch identifier built from a 64-bit local
 * counter wrapped in {@code long}. Mansart Transactions is a single-JVM coordinator (no
 * distributed XA across processes for now), so a simple counter is enough to disambiguate
 * concurrent transactions on the same JVM.
 *
 * <p>Format identifier {@link #FORMAT_ID} is fixed — chosen so it doesn't collide with the
 * common JBoss / Atomikos / Bitronix values that show up in recovery logs out in the wild.
 */
record MansartXid(byte[] gtrid, byte[] bqual) implements Xid {

    /** Mansart-specific format id — picked at random in the user-defined range (>= 0x4D414E53 = "MANS"). */
    static final int FORMAT_ID = 0x4D414E53;

    @Override public int    getFormatId()     { return FORMAT_ID; }
    @Override public byte[] getGlobalTransactionId() { return gtrid.clone(); }
    @Override public byte[] getBranchQualifier()     { return bqual.clone(); }

    @Override
    public String toString() {
        return "Xid{format=" + Integer.toHexString(FORMAT_ID)
                + ", gtrid=" + HexFormat.of().formatHex(gtrid)
                + ", bqual=" + HexFormat.of().formatHex(bqual) + "}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MansartXid x
                && Arrays.equals(gtrid, x.gtrid)
                && Arrays.equals(bqual, x.bqual);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(gtrid) + Arrays.hashCode(bqual);
    }
}
