package io.github.ragamuffincat.swiftload.io;

import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.natives.LibDeflate;
import io.github.ragamuffincat.swiftload.stats.Stats;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

// Stands in for vanilla's InflaterInputStream (region chunks) or GZIPInputStream (NBT files). The first read
// inflates everything with libdeflate, on the reading thread, since C2ME and Moonrise open region streams on one
// thread and read them on another.
// libdeflate only succeeds on a complete stream with a valid checksum. If it fails, the stream switches to vanilla's
// wrapper over the same bytes before serving anything, so a corrupt chunk fails the vanilla way.
public final class LazyInflaterInputStream extends InputStream {

    @FunctionalInterface
    public interface VanillaWrapper {
        InputStream wrap(InputStream compressed) throws IOException;
    }

    public enum Format {
        ZLIB, GZIP
    }

    private final Format format;
    private final VanillaWrapper vanilla;
    // Compressed input until the first access
    private InputStream source;
    // The inflated payload in fast mode
    private byte[] buf;
    private int pos;
    private int limit;
    // Vanilla's stream after a fallback
    private InputStream delegate;
    private Throwable fallbackReason;
    // Vanilla's wrapper itself threw (GZIPInputStream checks the header right away), so every access rethrows it.
    // Without this the next read returned EOF instead.
    private IOException wrapperFailure;
    private boolean closed;

    public LazyInflaterInputStream(InputStream compressed, Format format, VanillaWrapper vanilla) {
        this.source = Objects.requireNonNull(compressed);
        this.format = Objects.requireNonNull(format);
        this.vanilla = Objects.requireNonNull(vanilla);
    }

    // Valid up to inflatedLength(), null in fallback mode
    public byte[] inflated() throws IOException {
        ensureStarted();
        return buf;
    }

    public int inflatedLength() {
        return limit;
    }

    public boolean untouched() {
        return !closed && (source != null || (buf != null && pos == 0));
    }

    // Fast mode only. The fast NBT reader gave up, vanilla reads it from the start.
    public void rewind() {
        if (buf == null) {
            throw new IllegalStateException("not in fast mode");
        }
        pos = 0;
    }

    // Fast mode only. Leaves the stream where vanilla's read would have.
    public void seek(int position) {
        if (buf == null) {
            throw new IllegalStateException("not in fast mode");
        }
        pos = Objects.checkIndex(position, limit + 1);
    }

    public Throwable fallbackReason() {
        return fallbackReason;
    }

    @Override
    public int read() throws IOException {
        ensureStarted();
        if (delegate != null) {
            return delegate.read();
        }
        return pos < limit ? buf[pos++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        ensureStarted();
        if (delegate != null) {
            return delegate.read(b, off, len);
        }
        if (len == 0) {
            return 0;
        }
        int n = Math.min(len, limit - pos);
        if (n <= 0) {
            return -1;
        }
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        ensureStarted();
        if (delegate != null) {
            return delegate.skip(n);
        }
        if (n <= 0) {
            return 0;
        }
        int k = (int) Math.min(n, limit - pos);
        pos += k;
        return k;
    }

    @Override
    public int available() throws IOException {
        ensureStarted();
        if (delegate != null) {
            return delegate.available();
        }
        return limit - pos;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        buf = null;
        InputStream s = source;
        InputStream d = delegate;
        source = null;
        delegate = null;
        // Vanilla's stream closes the compressed source too
        if (d != null) {
            d.close();
        } else if (s != null) {
            s.close();
        }
    }

    private void ensureStarted() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (wrapperFailure != null) {
            throw wrapperFailure;
        }
        if (source == null) {
            return;
        }
        // Always an in-memory stream (RegionFile's ByteArrayInputStream, a file read into memory)
        byte[] compressed = source.readAllBytes();
        source = null;
        long t0 = Features.DEBUG_STATS ? System.nanoTime() : 0L;
        try {
            byte[] out = format == Format.ZLIB
                    ? LibDeflate.zlibDecompress(compressed, 0, compressed.length, 0)
                    : LibDeflate.gzipDecompress(compressed, 0, compressed.length);
            buf = out;
            limit = out.length;
            if (Features.DEBUG_STATS) {
                Stats.hit(feature(), System.nanoTime() - t0);
            }
        } catch (Throwable t) {
            fallbackReason = t;
            if (Features.DEBUG_STATS) {
                Stats.fallback(feature(), t);
            }
            try {
                delegate = vanilla.wrap(new ByteArrayInputStream(compressed));
            } catch (IOException e) {
                wrapperFailure = e;
                throw e;
            }
        }
    }

    private Stats.Feature feature() {
        return format == Format.ZLIB ? Stats.Feature.REGION_INFLATE : Stats.Feature.GZIP_FILES;
    }
}
