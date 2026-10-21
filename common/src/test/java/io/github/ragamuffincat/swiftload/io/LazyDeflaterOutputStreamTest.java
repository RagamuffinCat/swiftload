package io.github.ragamuffincat.swiftload.io;

import io.github.ragamuffincat.swiftload.natives.LibDeflate;
import io.github.ragamuffincat.swiftload.testdata.Tags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Whatever the stream writes, java.util.zip (and so vanilla) reads back unchanged
class LazyDeflaterOutputStreamTest {

    static final LazyDeflaterOutputStream.VanillaWrapper VANILLA_ZLIB = out -> new BufferedOutputStream(new DeflaterOutputStream(out));
    static final LazyDeflaterOutputStream.VanillaWrapper VANILLA_GZIP = out -> new BufferedOutputStream(new GZIPOutputStream(out));

    static List<byte[]> corpus;

    @BeforeAll
    static void setUp() throws IOException {
        LibDeflate.configure(Files.createTempDirectory("swiftload-test-natives"), true, true);
        assertTrue(LibDeflate.isAvailable(), LibDeflate.status());
        corpus = Tags.chunkCorpus(32);
    }

    // Records writes and close, like RegionFile's ChunkBuffer
    static final class Sink extends ByteArrayOutputStream {
        int writes;
        boolean closed;

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            writes++;
            super.write(b, off, len);
        }

        @Override
        public synchronized void write(int b) {
            writes++;
            super.write(b);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static byte[] vanilla(byte[] raw, LazyDeflaterOutputStream.VanillaWrapper w) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream o = w.wrap(bos)) {
            o.write(raw);
        }
        return bos.toByteArray();
    }

    @Test
    void zlibRoundTrip() throws IOException {
        long ours = 0, theirs = 0;
        for (byte[] raw : corpus) {
            Sink sink = new Sink();
            LazyDeflaterOutputStream out = new LazyDeflaterOutputStream(sink, LazyDeflaterOutputStream.Format.ZLIB, VANILLA_ZLIB);
            // Small writes, like DataOutputStream's
            for (int i = 0; i < raw.length; i += 777) {
                out.write(raw, i, Math.min(777, raw.length - i));
            }
            out.write(raw[0]);
            assertEquals(0, sink.size(), "nothing reaches the target before close (lazy)");
            out.close();
            assertTrue(sink.closed, "closes the target, as vanilla's chain does");
            assertEquals(1, sink.writes, "one write of the whole compressed chunk");
            byte[] expected = Arrays.copyOf(raw, raw.length + 1);
            expected[raw.length] = raw[0];
            assertArrayEquals(expected, new InflaterInputStream(new ByteArrayInputStream(sink.toByteArray())).readAllBytes());
            ours += sink.size();
            theirs += vanilla(expected, VANILLA_ZLIB).length;
        }
        double ratio = (double) ours / theirs;
        assertTrue(ratio <= 1.02, "libdeflate level 6 output " + ours + " vs java.util.zip " + theirs + " = " + ratio);
    }

    @Test
    void gzipRoundTrip() throws IOException {
        long ours = 0, theirs = 0;
        for (byte[] raw : corpus) {
            Sink sink = new Sink();
            try (LazyDeflaterOutputStream out = new LazyDeflaterOutputStream(sink, LazyDeflaterOutputStream.Format.GZIP, VANILLA_GZIP)) {
                out.write(raw);
            }
            assertArrayEquals(raw, new GZIPInputStream(new ByteArrayInputStream(sink.toByteArray())).readAllBytes());
            ours += sink.size();
            theirs += vanilla(raw, VANILLA_GZIP).length;
        }
        assertTrue((double) ours / theirs <= 1.02, ours + " vs " + theirs);
    }

    @Test
    void emptyStreamsAndClosingTwice() throws IOException {
        for (LazyDeflaterOutputStream.Format f : LazyDeflaterOutputStream.Format.values()) {
            Sink sink = new Sink();
            LazyDeflaterOutputStream out = new LazyDeflaterOutputStream(sink, f, f == LazyDeflaterOutputStream.Format.ZLIB ? VANILLA_ZLIB : VANILLA_GZIP);
            out.close();
            out.close();
            assertEquals(1, sink.writes);
            byte[] back = f == LazyDeflaterOutputStream.Format.ZLIB
                    ? new InflaterInputStream(new ByteArrayInputStream(sink.toByteArray())).readAllBytes()
                    : new GZIPInputStream(new ByteArrayInputStream(sink.toByteArray())).readAllBytes();
            assertEquals(0, back.length);
            assertThrows(IOException.class, () -> out.write(1));
            assertThrows(IOException.class, out::flush);
        }
    }

    @Test
    void largeBuffersGrowAndCompress() throws IOException {
        // Over the 1 MiB critical call limit, so this takes the copying path
        byte[] big = new byte[3 << 20];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 >>> 7);
        }
        Sink sink = new Sink();
        try (LazyDeflaterOutputStream out = new LazyDeflaterOutputStream(sink, LazyDeflaterOutputStream.Format.ZLIB, VANILLA_ZLIB)) {
            for (int i = 0; i < big.length; i += 4096) {
                out.write(big, i, 4096);
            }
        }
        assertArrayEquals(big, new InflaterInputStream(new ByteArrayInputStream(sink.toByteArray())).readAllBytes());
    }
}
