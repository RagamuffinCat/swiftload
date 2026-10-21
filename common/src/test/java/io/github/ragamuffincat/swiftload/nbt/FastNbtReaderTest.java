package io.github.ragamuffincat.swiftload.nbt;

import io.github.ragamuffincat.swiftload.testdata.Tags;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// Vanilla dialect against NbtIo.read on the same bytes: fast succeeds only when vanilla does, then the tags are equal
// and the accounter ends up the same
class FastNbtReaderTest {

    static final FastNbtReader READER = new FastNbtReader(FastNbtReader.Dialect.VANILLA, null, null);

    record Outcome(CompoundTag tag, long usage, int depth, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    static Outcome vanilla(byte[] b, NbtAccounter acc) {
        try {
            CompoundTag t = NbtIo.read(new DataInputStream(new ByteArrayInputStream(b)), acc);
            return new Outcome(t, acc.getUsage(), acc.getDepth(), null);
        } catch (Throwable e) {
            return new Outcome(null, acc.getUsage(), acc.getDepth(), e);
        }
    }

    static Outcome fast(byte[] b, NbtAccounter acc) {
        try {
            CompoundTag t = READER.readRoot(b, 0, b.length, acc);
            return new Outcome(t, acc.getUsage(), acc.getDepth(), null);
        } catch (Throwable e) {
            return new Outcome(null, acc.getUsage(), acc.getDepth(), e);
        }
    }

    // Returns vanilla's outcome
    static Outcome check(String what, byte[] b, Supplier<NbtAccounter> accounter) {
        Outcome v = vanilla(b, accounter.get());
        Outcome f = fast(b, accounter.get());
        if (f.ok() != v.ok()) {
            fail(what + ": fast " + (f.ok() ? "succeeded" : "failed (" + f.error + ")") + " but vanilla "
                    + (v.ok() ? "succeeded" : "failed (" + v.error + ")"));
        }
        if (v.ok()) {
            assertEquals(v.tag, f.tag, what + ": tags");
            assertEquals(v.usage, f.usage, what + ": accounter usage");
            assertEquals(0, f.depth, what + ": depth");
        }
        return v;
    }

    static Outcome check(String what, byte[] b) {
        return check(what, b, NbtAccounter::unlimitedHeap);
    }

    @Test
    void chunkCorpus() {
        List<byte[]> corpus = Tags.chunkCorpus(64);
        for (int i = 0; i < corpus.size(); i++) {
            assertTrue(check("chunk " + i, corpus.get(i)).ok());
        }
    }

    @Test
    void randomTrees() {
        Random rnd = new Random(0x5F1F7L);
        for (int i = 0; i < 2000; i++) {
            CompoundTag t = randomCompound(rnd, 0);
            Outcome v = check("tree " + i, Tags.bytes(t));
            assertTrue(v.ok());
            assertEquals(t, v.tag);
        }
    }

    @Test
    void sharedInstancesLikeVanilla() {
        CompoundTag t = new CompoundTag();
        t.putInt("small", 7);
        t.putByte("b", (byte) 1);
        t.putString("e", "");
        t.putFloat("negzero", -0.0f);
        t.putDouble("zero", 0.0);
        CompoundTag f = fast(Tags.bytes(t), NbtAccounter.unlimitedHeap()).tag;
        assertSame(IntTag.valueOf(7), f.get("small"));
        assertSame(ByteTag.valueOf((byte) 1), f.get("b"));
        assertSame(StringTag.valueOf(""), f.get("e"));
        assertSame(FloatTag.valueOf(0.0f), f.get("negzero"), "-0.0f loads as FloatTag.ZERO, as in vanilla");
        assertSame(DoubleTag.valueOf(0.0), f.get("zero"));
    }

    // One of every tag type
    static CompoundTag everyType() {
        CompoundTag t = new CompoundTag();
        for (int type = 1; type <= 12; type++) {
            if (type == 9 || type == 10) {
                continue;
            }
            t.put("t" + type, randomTag(new Random(type), 3, type));
        }
        ListTag mixed = new ListTag();
        mixed.add(StringTag.valueOf("minecraft:stone"));
        mixed.add(IntTag.valueOf(5));
        t.put("mixed", mixed);
        CompoundTag inner = new CompoundTag();
        inner.putString("unicode", "\u00e9\u4e16\ud83c\udf32");
        t.put("inner", inner);
        return t;
    }

    @Test
    void truncatedAtEveryOffset() {
        byte[] b = Tags.bytes(everyType());
        for (int cut = 0; cut < b.length; cut++) {
            Outcome v = check("cut " + cut, Arrays.copyOf(b, cut));
            assertNotNull(v.error, "a truncated compound never parses (cut " + cut + ")");
        }
    }

    @Test
    void depthLimit() throws IOException {
        for (int depth : new int[]{511, 512, 513, 600}) {
            CompoundTag root = new CompoundTag();
            CompoundTag cur = root;
            for (int i = 1; i < depth; i++) {
                CompoundTag next = new CompoundTag();
                cur.put("c", next);
                cur = next;
            }
            Outcome v = check("depth " + depth, Tags.bytes(root));
            assertEquals(depth <= 512, v.ok(), "depth " + depth);
        }
        // Lists count too
        CompoundTag root = new CompoundTag();
        ListTag l = new ListTag();
        root.put("l", l);
        for (int i = 2; i < 520; i++) {
            ListTag n = new ListTag();
            l.add(n);
            l = n;
        }
        assertNotNull(check("deep lists", Tags.bytes(root)).error);
    }

    @Test
    void quotaBoundary() {
        for (byte[] b : List.of(Tags.chunkCorpus(1).get(0), Tags.bytes(randomCompound(new Random(9), 0)))) {
            long usage = vanilla(b, NbtAccounter.unlimitedHeap()).usage;
            assertTrue(check("exact quota", b, () -> NbtAccounter.create(usage)).ok());
            assertNotNull(check("quota - 1", b, () -> NbtAccounter.create(usage - 1)).error);
            assertNotNull(check("small quota", b, () -> NbtAccounter.create(100)).error);
        }
    }

    @Test
    void handcrafted() throws IOException {
        check("empty", new byte[0]);
        check("end root", new byte[]{0});
        check("string root", raw(o -> { o.writeByte(8); o.writeUTF(""); o.writeUTF("x"); }));
        check("int root", raw(o -> { o.writeByte(3); o.writeUTF("n"); o.writeInt(5); }));
        check("root name skipped even if malformed", raw(o -> { o.writeByte(10); o.writeShort(2); o.write(0xFF); o.write(0xFE); o.writeByte(0); }));
        check("root name longer than input", raw(o -> { o.writeByte(10); o.writeShort(500); o.writeByte(0); }));
        check("trailing bytes ignored", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(0); o.writeInt(0xDEADBEEF); }));
        check("duplicate keys", raw(o -> {
            o.writeByte(10); o.writeUTF("");
            o.writeByte(3); o.writeUTF("k"); o.writeInt(1);
            o.writeByte(8); o.writeUTF("k"); o.writeUTF("second wins");
            o.writeByte(0);
        }));
        check("invalid tag id", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(13); o.writeUTF("k"); o.writeByte(0); }));
        check("negative tag id", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(-1); o.writeUTF("k"); o.writeByte(0); }));
        for (int type : new int[]{7, 11, 12}) {
            check("negative array length " + type, raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(type); o.writeUTF("a"); o.writeInt(-1); o.writeByte(0); }));
            check("huge array length " + type, raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(type); o.writeUTF("a"); o.writeInt(Integer.MAX_VALUE); o.writeByte(0); }),
                    () -> NbtAccounter.create(1 << 20));
        }
        check("negative list length", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(9); o.writeUTF("l"); o.writeByte(3); o.writeInt(-5); o.writeByte(0); }));
        check("list missing type", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(9); o.writeUTF("l"); o.writeByte(0); o.writeInt(2); o.writeByte(0); }));
        check("empty END list", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(9); o.writeUTF("l"); o.writeByte(0); o.writeInt(0); o.writeByte(0); }));
        check("invalid element type, empty", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(9); o.writeUTF("l"); o.writeByte(99); o.writeInt(0); o.writeByte(0); }));
        check("invalid element type, one", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(9); o.writeUTF("l"); o.writeByte(99); o.writeInt(1); o.writeByte(0); }));
        check("huge list count", raw(o -> { o.writeByte(10); o.writeUTF(""); o.writeByte(9); o.writeUTF("l"); o.writeByte(1); o.writeInt(Integer.MAX_VALUE); o.writeByte(0); }),
                () -> NbtAccounter.create(1 << 20));
        check("unwrap on load", raw(o -> {
            o.writeByte(10); o.writeUTF("");
            o.writeByte(9); o.writeUTF("l"); o.writeByte(10); o.writeInt(5);
            o.writeByte(8); o.writeUTF(""); o.writeUTF("minecraft:stone"); o.writeByte(0);
            o.writeByte(10); o.writeUTF(""); o.writeByte(3); o.writeUTF(""); o.writeInt(7); o.writeByte(0); o.writeByte(0);
            o.writeByte(3); o.writeUTF("a"); o.writeInt(1); o.writeByte(0);
            o.writeByte(3); o.writeUTF(""); o.writeInt(1); o.writeByte(3); o.writeUTF("b"); o.writeInt(2); o.writeByte(0);
            o.writeByte(0);
            o.writeByte(0);
        }));
    }

    @Test
    void malformedModifiedUtf8() throws IOException {
        int[][] bad = {{0x80}, {0xC0}, {0xC0, 0x00}, {0xE0, 0x80}, {0xE0, 0x80, 0x00}, {0xF0, 0x80, 0x80, 0x80}, {0xFF},
                {'a', 'b', 0xED, 0xA0}, {0xC2, 0xA9, 0xE2, 0x82, 0xAC}, {0x00, 0xC0, 0x80}, {0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80}};
        for (int[] s : bad) {
            for (boolean asKey : new boolean[]{true, false}) {
                byte[] b = raw(o -> {
                    o.writeByte(10);
                    o.writeUTF("");
                    o.writeByte(8);
                    if (asKey) {
                        writeRaw(o, s);
                        o.writeUTF("v");
                    } else {
                        o.writeUTF("k");
                        writeRaw(o, s);
                    }
                    o.writeByte(0);
                });
                check("utf " + Arrays.toString(s) + (asKey ? " key" : " value"), b);
            }
        }
    }

    @Test
    void mutationFuzz() {
        Random rnd = new Random(1234);
        List<byte[]> seeds = new ArrayList<>(Tags.chunkCorpus(4));
        for (int i = 0; i < 40; i++) {
            seeds.add(Tags.bytes(randomCompound(rnd, 0)));
        }
        int vanillaOk = 0, total = 0;
        // Every vanilla failure builds a CrashReport (about 25 ms), so the default run is small.
        // -Pswiftload.fuzz=<mutations per seed> runs more.
        int perSeed = Integer.getInteger("swiftload.fuzz", 30);
        for (byte[] seed : seeds) {
            for (int m = 0; m < perSeed; m++) {
                byte[] b = mutate(seed, rnd);
                // The quota keeps vanilla from allocating absurd lists and arrays from mutated lengths
                Outcome v = check("mutation", b, () -> NbtAccounter.create(32L << 20));
                total++;
                if (v.ok()) {
                    vanillaOk++;
                }
            }
        }
        assertTrue(vanillaOk > total / 20, "fuzz should keep a share of parseable inputs: " + vanillaOk + "/" + total);
    }

    @Test
    void selfTestPasses() {
        assertNull(FastNbt.neoForgeAccountUtf(), "vanilla NbtAccounter has no readUTF(String)");
        assertEquals(FastNbtReader.Dialect.VANILLA, FastNbt.newReader(null).dialect());
        assertNull(FastNbt.selfTest(FastNbt.newReader(null)));
    }

    @Test
    void selfTestCatchesWrongDialect() throws Exception {
        MethodHandle fake = MethodHandles.lookup().findStatic(FastNbtReaderTest.class,
                "fakeAccountUtf", MethodType.methodType(String.class, NbtAccounter.class, String.class));
        String problem = FastNbt.selfTest(new FastNbtReader(FastNbtReader.Dialect.NEOFORGE, fake, null));
        assertNotNull(problem);
    }

    static String fakeAccountUtf(NbtAccounter acc, String s) {
        acc.accountBytes(2L + s.length());
        return s;
    }

    @Test
    void positionIsWhereVanillaStops() throws IOException {
        byte[] b = raw(o -> { o.writeByte(10); o.writeUTF("name"); o.writeByte(1); o.writeUTF("x"); o.writeByte(5); o.writeByte(0); o.writeInt(99); });
        READER.readRoot(b, 0, b.length, NbtAccounter.unlimitedHeap());
        assertEquals(b.length - 4, READER.position());
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        NbtIo.read(in, NbtAccounter.unlimitedHeap());
        assertEquals(4, in.available());
    }

    interface Writer {
        void write(DataOutputStream o) throws IOException;
    }

    static byte[] raw(Writer w) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        w.write(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static void writeRaw(DataOutputStream o, int[] bytes) throws IOException {
        o.writeShort(bytes.length);
        for (int x : bytes) {
            o.write(x);
        }
    }

    static byte[] mutate(byte[] seed, Random rnd) {
        byte[] b = seed.clone();
        switch (rnd.nextInt(5)) {
            case 0 -> b[rnd.nextInt(b.length)] ^= (byte) (1 << rnd.nextInt(8));
            case 1 -> b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
            case 2 -> b = Arrays.copyOf(b, rnd.nextInt(b.length));
            // Overwrites 4 bytes (lengths, counts)
            case 3 -> {
                int p = rnd.nextInt(Math.max(1, b.length - 4));
                for (int i = 0; i < 4 && p + i < b.length; i++) {
                    b[p + i] = (byte) rnd.nextInt(256);
                }
            }
            default -> {
                for (int i = 0; i < 4; i++) {
                    b[rnd.nextInt(b.length)] ^= (byte) (1 << rnd.nextInt(8));
                }
            }
        }
        return b;
    }

    static final String[] KEYS = {"id", "x", "Y", "palette", "data", "block_states", "", "\u00e9t\u00e9", "\u4e16", "k\u0000", "a long key that does not fit the thirty-two byte key cache"};

    static String randomString(Random rnd) {
        int n = rnd.nextInt(12);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            switch (rnd.nextInt(6)) {
                case 0 -> sb.append((char) 0);
                case 1 -> sb.append((char) (0x80 + rnd.nextInt(0x780)));
                case 2 -> sb.append((char) (0x800 + rnd.nextInt(0xF000)));
                case 3 -> sb.append("\ud83c\udf32");
                default -> sb.append((char) ('a' + rnd.nextInt(26)));
            }
        }
        return sb.toString();
    }

    static Tag randomTag(Random rnd, int depth, int type) {
        return switch (type) {
            case 1 -> ByteTag.valueOf((byte) rnd.nextInt());
            case 2 -> ShortTag.valueOf((short) (rnd.nextBoolean() ? rnd.nextInt(2000) - 200 : rnd.nextInt()));
            case 3 -> IntTag.valueOf(rnd.nextBoolean() ? rnd.nextInt(2000) - 200 : rnd.nextInt());
            case 4 -> LongTag.valueOf(rnd.nextBoolean() ? rnd.nextInt(2000) - 200 : rnd.nextLong());
            case 5 -> FloatTag.valueOf(rnd.nextInt(4) == 0 ? -0.0f : rnd.nextFloat() * 1000 - 500);
            case 6 -> DoubleTag.valueOf(rnd.nextInt(4) == 0 ? 0.0 : rnd.nextGaussian());
            case 7 -> {
                byte[] a = new byte[rnd.nextInt(40)];
                rnd.nextBytes(a);
                yield new ByteArrayTag(a);
            }
            case 8 -> StringTag.valueOf(randomString(rnd));
            case 9 -> randomList(rnd, depth + 1);
            case 10 -> randomCompound(rnd, depth + 1);
            case 11 -> new IntArrayTag(rnd.ints(rnd.nextInt(20)).toArray());
            default -> new LongArrayTag(rnd.longs(rnd.nextInt(20)).toArray());
        };
    }

    static ListTag randomList(Random rnd, int depth) {
        ListTag l = new ListTag();
        int n = depth > 4 ? 0 : rnd.nextInt(6);
        boolean mixed = rnd.nextInt(4) == 0;
        int type = 1 + rnd.nextInt(12);
        for (int i = 0; i < n; i++) {
            int t = mixed ? 1 + rnd.nextInt(12) : type;
            if (depth > 4 && (t == 9 || t == 10)) {
                t = 3;
            }
            Tag e = randomTag(rnd, depth, t);
            // An element that is itself a wrapper-shaped compound
            if (rnd.nextInt(10) == 0) {
                CompoundTag w = new CompoundTag();
                w.put("", e);
                e = w;
            }
            l.add(e);
        }
        return l;
    }

    static CompoundTag randomCompound(Random rnd, int depth) {
        CompoundTag c = new CompoundTag();
        int n = depth > 4 ? rnd.nextInt(2) : 1 + rnd.nextInt(7);
        for (int i = 0; i < n; i++) {
            String key = rnd.nextInt(3) == 0 ? randomString(rnd) : KEYS[rnd.nextInt(KEYS.length)];
            int t = 1 + rnd.nextInt(12);
            if (depth > 4 && (t == 9 || t == 10)) {
                t = 8;
            }
            c.put(key, randomTag(rnd, depth, t));
        }
        return c;
    }
}
