package io.github.ragamuffincat.swiftload.io;

import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.natives.LibDeflate;
import io.github.ragamuffincat.swiftload.stats.Stats;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

// Stands in for vanilla's DeflaterOutputStream (region chunks) or GZIPOutputStream (NBT files). Buffers everything,
// and close() compresses it with libdeflate and writes it to out in one go. If libdeflate fails, the buffer goes
// through vanilla's wrapper instead.
public final class LazyDeflaterOutputStream extends OutputStream {

    @FunctionalInterface
    public interface VanillaWrapper {
        OutputStream wrap(OutputStream out) throws IOException;
    }

    public enum Format {
        ZLIB, GZIP
    }

    // java.util.zip uses zlib's default level 6, libdeflate's 6 is the closest match
    public static final int LEVEL = 6;

    private final OutputStream out;
    private final Format format;
    private final VanillaWrapper vanilla;
    private byte[] buf = new byte[16 * 1024];
    private int count;
    private boolean closed;

    public LazyDeflaterOutputStream(OutputStream out, Format format, VanillaWrapper vanilla) {
        this.out = Objects.requireNonNull(out);
        this.format = Objects.requireNonNull(format);
        this.vanilla = Objects.requireNonNull(vanilla);
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        if (count == buf.length) {
            grow(count + 1);
        }
        buf[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        ensureOpen();
        if (len > buf.length - count) {
            grow(count + len);
        }
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    // There's no compressed stream to flush before close
    @Override
    public void flush() throws IOException {
        ensureOpen();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        long t0 = Features.DEBUG_STATS ? System.nanoTime() : 0L;
        byte[] compressed;
        try {
            compressed = format == Format.ZLIB
                    ? LibDeflate.zlibCompress(buf, 0, count, LEVEL)
                    : LibDeflate.gzipCompress(buf, 0, count, LEVEL);
        } catch (Throwable t) {
            if (Features.DEBUG_STATS) {
                Stats.fallback(Stats.Feature.FAST_WRITE, t);
            }
            // Closing it closes out, as vanilla's chain does
            try (OutputStream v = vanilla.wrap(out)) {
                v.write(buf, 0, count);
            }
            buf = null;
            return;
        }
        buf = null;
        try {
            out.write(compressed);
        } finally {
            out.close();
        }
        if (Features.DEBUG_STATS) {
            Stats.hit(Stats.Feature.FAST_WRITE, System.nanoTime() - t0);
        }
    }

    int size() {
        return count;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    private void grow(int min) {
        if (min < 0) {
            throw new OutOfMemoryError("buffer over 2 GiB");
        }
        int n = (int) Math.min(Integer.MAX_VALUE - 16L, Math.max(min, buf.length * 2L));
        if (n < min) {
            throw new OutOfMemoryError("buffer over 2 GiB");
        }
        buf = Arrays.copyOf(buf, n);
    }
}
