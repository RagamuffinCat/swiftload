package io.github.ragamuffincat.swiftload.io;

import io.github.ragamuffincat.swiftload.natives.LibDeflate;
import io.github.ragamuffincat.swiftload.testdata.Tags;
import net.minecraft.util.FastBufferedInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Against vanilla's region stream chain, FastBufferedInputStream(InflaterInputStream(in))
class LazyInflaterInputStreamTest {

    // RegionFileVersion.VERSION_DEFLATE's input wrapper
    static final LazyInflaterInputStream.VanillaWrapper VANILLA = in -> new FastBufferedInputStream(new InflaterInputStream(in));

    static List<byte[]> corpus;

    @BeforeAll
    static void setUp() throws IOException {
        LibDeflate.configure(Files.createTempDirectory("swiftload-test-natives"), true, true);
        assertTrue(LibDeflate.isAvailable(), LibDeflate.status());
        corpus = Tags.chunkCorpus(16);
    }

    // What VERSION_DEFLATE writes: DeflaterOutputStream at the default level
    static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bos)) {
            out.write(raw);
        }
        return bos.toByteArray();
    }

    static LazyInflaterInputStream lazy(byte[] compressed) {
        return new LazyInflaterInputStream(new ByteArrayInputStream(compressed), LazyInflaterInputStream.Format.ZLIB, VANILLA);
    }

    // The bytes read to the end, or the exception's class and message
    static String drain(InputStream in) {
        try (in) {
            byte[] b = in.readAllBytes();
            return "ok " + b.length + " " + Arrays.hashCode(b);
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    @Test
    void sameBytesAsVanillaOnTheCorpus() throws IOException {
        for (byte[] raw : corpus) {
            byte[] z = deflate(raw);
            LazyInflaterInputStream in = lazy(z);
            assertArrayEquals(raw, in.readAllBytes());
            assertNull(in.fallbackReason());
            assertArrayEquals(VANILLA.wrap(new ByteArrayInputStream(z)).readAllBytes(), raw);
        }
    }

    @Test
    void corruptInputLikeVanilla() throws IOException {
        byte[] z = deflate(corpus.get(3));
        Random rnd = new Random(42);
        int fallbacks = 0;
        for (int cut = 0; cut < z.length; cut += Math.max(1, z.length / 300)) {
            byte[] t = Arrays.copyOf(z, cut);
            assertEquals(drain(VANILLA.wrap(new ByteArrayInputStream(t))), drain(lazy(t)), "truncated at " + cut);
        }
        for (int i = 0; i < 400; i++) {
            byte[] c = z.clone();
            int flips = 1 + rnd.nextInt(3);
            for (int k = 0; k < flips; k++) {
                c[rnd.nextInt(c.length)] ^= (byte) (1 << rnd.nextInt(8));
            }
            LazyInflaterInputStream in = lazy(c);
            String ours = drain(in);
            assertEquals(drain(VANILLA.wrap(new ByteArrayInputStream(c))), ours, "mutation " + i);
            if (in.fallbackReason() != null) {
                fallbacks++;
            }
        }
        assertTrue(fallbacks > 300, "corrupt data must go through vanilla's wrapper: " + fallbacks);
        // Both ignore garbage after a complete stream
        byte[] trailing = Arrays.copyOf(z, z.length + 7);
        assertEquals(drain(VANILLA.wrap(new ByteArrayInputStream(trailing))), drain(lazy(trailing)));
        byte[] junk = "definitely not zlib".getBytes();
        assertEquals(drain(VANILLA.wrap(new ByteArrayInputStream(junk))), drain(lazy(junk)));
    }

    @Test
    void dataInputOperationsMatchVanilla() throws IOException {
        byte[] raw = corpus.get(1);
        byte[] z = deflate(raw);
        DataInputStream v = new DataInputStream(VANILLA.wrap(new ByteArrayInputStream(z)));
        SwiftDataInputStream f = new SwiftDataInputStream(lazy(z));
        Random rnd = new Random(7);
        while (true) {
            int op = rnd.nextInt(6);
            String a = op(v, op, rnd.nextInt(5000)), b = op(f, op, -1);
            assertEquals(a, b);
            if (a.startsWith("java.io.EOFException")) {
                break;
            }
        }
    }

    private int lastArg;

    private String op(DataInputStream in, int op, int arg) {
        if (arg >= 0) {
            lastArg = arg;
        }
        int n = lastArg;
        try {
            return switch (op) {
                case 0 -> "byte " + in.readByte();
                case 1 -> "int " + in.readInt();
                case 2 -> "long " + in.readLong();
                case 3 -> "skip " + in.skipBytes(n);
                case 4 -> {
                    byte[] b = new byte[n];
                    in.readFully(b);
                    yield "fully " + Arrays.hashCode(b);
                }
                // Not a raw read(byte[]): it may legally return fewer bytes, and vanilla's buffered chain does.
                // NbtIo only uses the DataInput methods above.
                default -> "utf " + in.readUTF().hashCode();
            };
        } catch (IOException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    @Test
    void nothingHappensBeforeFirstRead() throws IOException {
        byte[] z = deflate(corpus.get(0));
        int[] reads = {0};
        InputStream counting = new ByteArrayInputStream(z) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                reads[0]++;
                return super.read(b, off, len);
            }

            @Override
            public synchronized byte[] readAllBytes() {
                reads[0]++;
                return super.readAllBytes();
            }
        };
        LazyInflaterInputStream in = new LazyInflaterInputStream(counting, LazyInflaterInputStream.Format.ZLIB, VANILLA);
        assertEquals(0, reads[0], "constructor must not touch the source (C2ME/Moonrise read on another thread)");
        assertTrue(in.untouched());
        assertEquals(corpus.get(0)[0] & 0xFF, in.read());
        assertTrue(reads[0] > 0);
        assertFalse(in.untouched());
    }

    @Test
    void fastModeAccessRewindSeekClose() throws IOException {
        byte[] raw = corpus.get(2);
        LazyInflaterInputStream in = lazy(deflate(raw));
        byte[] inflated = in.inflated();
        assertNotNull(inflated);
        assertEquals(raw.length, in.inflatedLength());
        assertArrayEquals(raw, Arrays.copyOf(inflated, in.inflatedLength()));
        assertTrue(in.untouched(), "inflating for the fast path does not consume");
        in.skip(10);
        assertFalse(in.untouched());
        in.rewind();
        assertTrue(in.untouched());
        in.seek(raw.length);
        assertEquals(-1, in.read());
        assertEquals(0, in.available());
        in.close();
        assertThrows(IOException.class, in::read);
        assertFalse(in.untouched());
    }

    @Test
    void failedInflateFallsBack() throws IOException {
        byte[] bad = deflate(corpus.get(0));
        bad[bad.length / 2] ^= 0x55;
        LazyInflaterInputStream in = lazy(bad);
        assertNull(in.inflated(), "fast NBT must not run on a stream in fallback mode");
        assertNotNull(in.fallbackReason());
        assertFalse(in.untouched());
        assertThrows(IllegalStateException.class, in::rewind);
        assertEquals(drain(VANILLA.wrap(new ByteArrayInputStream(bad))), drain(in));
    }

    @Test
    void wrapperFailureIsRethrown() {
        ZipException boom = new ZipException("Not in GZIP format");
        LazyInflaterInputStream in = new LazyInflaterInputStream(new ByteArrayInputStream("junk".getBytes()),
                LazyInflaterInputStream.Format.GZIP, s -> {
                    throw boom;
                });
        assertTrue(assertThrows(IOException.class, in::inflated) == boom);
        assertTrue(assertThrows(IOException.class, in::read) == boom, "not EOF: vanilla never got a stream");
        assertTrue(assertThrows(IOException.class, () -> in.read(new byte[4], 0, 4)) == boom);
        assertFalse(in.untouched());
    }

    @Test
    void emptySourceAndZeroLengthReads() throws IOException {
        LazyInflaterInputStream empty = lazy(new byte[0]);
        assertEquals(drain(VANILLA.wrap(new ByteArrayInputStream(new byte[0]))), drain(empty));
        LazyInflaterInputStream in = lazy(deflate(new byte[]{1, 2, 3}));
        assertEquals(0, in.read(new byte[4], 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[4], 3, 2));
        assertEquals(1, in.read());
        Objects.requireNonNull(in);
    }
}
