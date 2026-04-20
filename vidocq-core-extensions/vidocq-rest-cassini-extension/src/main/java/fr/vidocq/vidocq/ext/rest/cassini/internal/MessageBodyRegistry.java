package fr.vidocq.vidocq.ext.rest.cassini.internal;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Registre des {@link MessageBodyReader} / {@link MessageBodyWriter}
 * utilisés pour sérialiser entités requête/réponse (§4.2).
 *
 * <p>M2c : providers built-in pour {@code String}, {@code byte[]},
 * {@code InputStream}, {@code Reader}, {@link StreamingOutput}, {@link File}.
 * Le scan de {@code @Provider} utilisateur arrive en M2f.</p>
 */
public final class MessageBodyRegistry {

    private final List<MessageBodyReader<?>> readers = new ArrayList<>();
    private final List<MessageBodyWriter<?>> writers = new ArrayList<>();

    public MessageBodyRegistry() {
        registerBuiltins();
    }

    public void addReader(MessageBodyReader<?> r) { readers.add(0, r); }
    public void addWriter(MessageBodyWriter<?> w) { writers.add(0, w); }

    @SuppressWarnings("unchecked")
    public <T> Optional<MessageBodyReader<T>> findReader(Class<T> type, Type genericType,
                                                         Annotation[] anns, MediaType mt) {
        for (MessageBodyReader<?> r : readers) {
            if (r.isReadable(type, genericType, anns, mt)) {
                return Optional.of((MessageBodyReader<T>) r);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<MessageBodyWriter<T>> findWriter(Class<?> type, Type genericType,
                                                         Annotation[] anns, MediaType mt) {
        for (MessageBodyWriter<?> w : writers) {
            if (w.isWriteable(type, genericType, anns, mt)) {
                return Optional.of((MessageBodyWriter<T>) w);
            }
        }
        return Optional.empty();
    }

    private void registerBuiltins() {
        // The last added has priority (addXxx inserts at 0), so order matters.
        writers.add(new ByteArrayWriter());
        writers.add(new StringWriter());
        writers.add(new StreamingOutputWriter());
        writers.add(new InputStreamWriter());
        writers.add(new FileWriter());

        readers.add(new ByteArrayReader());
        readers.add(new StringReader());
        readers.add(new InputStreamReaderMBR());
        readers.add(new ReaderReaderMBR());
    }

    private static Charset charset(MediaType mt) {
        if (mt == null) return StandardCharsets.UTF_8;
        String cs = mt.getParameters().get("charset");
        try {
            return cs == null ? StandardCharsets.UTF_8 : Charset.forName(cs);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    // ---- String ----
    static final class StringWriter implements MessageBodyWriter<String> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return String.class.isAssignableFrom(t); }
        @Override public void writeTo(String v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            s.write(v.getBytes(charset(mt)));
        }
    }
    static final class StringReader implements MessageBodyReader<String> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return String.class == t; }
        @Override public String readFrom(Class<String> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) throws IOException {
            return new String(in.readAllBytes(), charset(mt));
        }
    }

    // ---- byte[] ----
    static final class ByteArrayWriter implements MessageBodyWriter<byte[]> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return byte[].class == t; }
        @Override public void writeTo(byte[] v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            s.write(v);
        }
    }
    static final class ByteArrayReader implements MessageBodyReader<byte[]> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return byte[].class == t; }
        @Override public byte[] readFrom(Class<byte[]> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) throws IOException {
            return in.readAllBytes();
        }
    }

    // ---- InputStream ----
    static final class InputStreamWriter implements MessageBodyWriter<InputStream> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return InputStream.class.isAssignableFrom(t); }
        @Override public void writeTo(InputStream v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            v.transferTo(s);
        }
    }
    static final class InputStreamReaderMBR implements MessageBodyReader<InputStream> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return InputStream.class == t; }
        @Override public InputStream readFrom(Class<InputStream> t, Type gt, Annotation[] a, MediaType mt,
                                              MultivaluedMap<String, String> h, InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return new java.io.ByteArrayInputStream(bos.toByteArray());
        }
    }

    // ---- Reader ----
    static final class ReaderReaderMBR implements MessageBodyReader<Reader> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return Reader.class == t; }
        @Override public Reader readFrom(Class<Reader> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) {
            return new BufferedReader(new InputStreamReader(in, charset(mt)));
        }
    }

    // ---- StreamingOutput ----
    static final class StreamingOutputWriter implements MessageBodyWriter<StreamingOutput> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return StreamingOutput.class.isAssignableFrom(t); }
        @Override public void writeTo(StreamingOutput v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            v.write(s);
        }
    }

    // ---- File ----
    static final class FileWriter implements MessageBodyWriter<File> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return File.class.isAssignableFrom(t); }
        @Override public void writeTo(File v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            try (InputStream in = new FileInputStream(v)) { in.transferTo(s); }
        }
    }

    /** Fabrique un MultivaluedMap<String, String> depuis les headers Chappe. */
    public static MultivaluedMap<String, String> adaptHeaders(fr.vidocq.chappe.api.Headers h) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (fr.vidocq.chappe.api.Headers.Entry e : h) {
            m.add(e.name(), e.value());
        }
        return m;
    }

    /** Utilitaire : force un MultivaluedMap<String, Object> pour sortie MBW. */
    public static MultivaluedMap<String, Object> outHeaders() {
        return new MultivaluedHashMap<>();
    }

    /** Helper qui suppress le type-cast nécessaire au writeTo typé. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void writeTo(MessageBodyWriter w, Object value, Class<?> type,
                               Type genericType, Annotation[] anns, MediaType mt,
                               MultivaluedMap<String, Object> headers, OutputStream os) throws IOException {
        w.writeTo(value, type, genericType, anns, mt, headers, os);
    }
}
