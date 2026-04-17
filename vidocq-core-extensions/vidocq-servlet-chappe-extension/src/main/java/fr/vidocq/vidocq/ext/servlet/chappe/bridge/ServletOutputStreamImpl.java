package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * {@link ServletOutputStream} qui accumule dans un {@link ByteArrayOutputStream} interne.
 * Le contenu est transféré à la {@link fr.vidocq.chappe.api.Response} en fin de dispatch.
 *
 * <p><em>Non-blocking I/O n'est pas implémenté dans ce jalon.</em></p>
 */
public final class ServletOutputStreamImpl extends ServletOutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean closed;

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        throw new UnsupportedOperationException("non-blocking write not implemented");
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) throw new IOException("stream closed");
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("stream closed");
        buffer.write(b, off, len);
    }

    @Override
    public void flush() {
        // Buffer accumulation only — real flush happens on response commit.
    }

    @Override
    public void close() {
        closed = true;
    }

    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    public int size() {
        return buffer.size();
    }
}
