package io.github.ragamuffincat.swiftload.nbt;

import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.Swiftload;
import io.github.ragamuffincat.swiftload.io.LazyDeflaterOutputStream;
import io.github.ragamuffincat.swiftload.io.LazyInflaterInputStream;
import io.github.ragamuffincat.swiftload.io.SwiftDataInputStream;
import io.github.ragamuffincat.swiftload.mixin.ListTagInvoker;
import io.github.ragamuffincat.swiftload.mixin.NbtAccounterAccessor;
import io.github.ragamuffincat.swiftload.mixin.NbtIoInvoker;
import io.github.ragamuffincat.swiftload.stats.Stats;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// Runs FastNbtReader for NbtIoMixin. The reader's accounting dialect is checked against the game's own parser
// once, before first use.
public final class FastNbt {

    private FastNbt() {
    }

    // Null means vanilla reads the stream. Never throws.
    public static CompoundTag tryRead(LazyInflaterInputStream in, NbtAccounter accounter) {
        if (!Holder.ENABLED || !in.untouched()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = in.inflated();
        } catch (Throwable t) {
            // The stream throws it again for vanilla's read
            return null;
        }
        if (bytes == null) {
            // Already on vanilla's wrapper
            return null;
        }
        long usage = accounter.getUsage();
        int depth = accounter.getDepth();
        long t0 = Features.DEBUG_STATS ? System.nanoTime() : 0L;
        FastNbtReader reader = Holder.READER.get();
        try {
            CompoundTag tag = reader.readRoot(bytes, 0, in.inflatedLength(), accounter);
            in.seek(reader.position());
            if (Features.DEBUG_STATS) {
                Stats.hit(Stats.Feature.FAST_NBT, System.nanoTime() - t0);
            }
            return tag;
        } catch (Throwable t) {
            // Swiftload.LOG.info("fast nbt gave up at {}: {}", reader.position(), t.toString());
            // Vanilla reads the same bytes from the start, with the accounter as it was
            NbtAccounterAccessor a = (NbtAccounterAccessor) accounter;
            a.swiftload$setUsage(usage);
            a.swiftload$setDepth(depth);
            in.rewind();
            if (Features.DEBUG_STATS) {
                Stats.fallback(Stats.Feature.FAST_NBT, t);
            }
            return null;
        }
    }

    // Inflates the whole file at once and parses it through NbtIo.read, so tryRead applies. If libdeflate rejects
    // it, NbtIo's own gzip stream reads the same bytes. Closes in, like vanilla. Checked exceptions pass through,
    // the hooked method declares them.
    public static CompoundTag readCompressed(InputStream in, NbtAccounter accounter) {
        try (InputStream source = in;
             SwiftDataInputStream data = new SwiftDataInputStream(new LazyInflaterInputStream(
                     new ByteArrayInputStream(source.readAllBytes()), LazyInflaterInputStream.Format.GZIP,
                     NbtIoInvoker::swiftload$createDecompressorStream))) {
            return NbtIo.read(data, accounter);
        } catch (IOException e) {
            throw sneaky(e);
        }
    }

    // Same structure as vanilla's writeCompressed, so a failed write still closes the stream and writes what it has
    public static void writeCompressed(CompoundTag tag, OutputStream out) {
        try (DataOutputStream dos = new DataOutputStream(new LazyDeflaterOutputStream(out,
                LazyDeflaterOutputStream.Format.GZIP, NbtIoInvoker::swiftload$createCompressorStream))) {
            NbtIo.write(tag, dos);
        } catch (IOException e) {
            throw sneaky(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }

    public static boolean enabled() {
        return Holder.ENABLED;
    }

    private static final MethodHandle ACCOUNT_UTF = findAccountUtf();

    // NeoForge's NbtAccounter.readUTF(String), null on Fabric
    static MethodHandle neoForgeAccountUtf() {
        return ACCOUNT_UTF;
    }

    private static MethodHandle findAccountUtf() {
        try {
            return MethodHandles.lookup().findVirtual(NbtAccounter.class, "readUTF",
                    MethodType.methodType(String.class, String.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    public static FastNbtReader newReader(Function<List<Tag>, ListTag> listFactory) {
        MethodHandle utf = neoForgeAccountUtf();
        return utf != null
                ? new FastNbtReader(FastNbtReader.Dialect.NEOFORGE, utf, listFactory)
                : new FastNbtReader(FastNbtReader.Dialect.VANILLA, null, listFactory);
    }

    // Reads a sample with every tag type through NbtIo.read and through reader. Returns null if tags and accounting
    // match, else what differs. Catches a loader or mod that changed how NBT is read.
    public static String selfTest(FastNbtReader reader) {
        try {
            byte[] sample = sample();
            NbtAccounter vanillaAcc = NbtAccounter.unlimitedHeap();
            CompoundTag expected = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample)), vanillaAcc);
            NbtAccounter fastAcc = NbtAccounter.unlimitedHeap();
            CompoundTag actual = reader.readRoot(sample, 0, sample.length, fastAcc);
            if (!expected.equals(actual)) {
                return "tags differ";
            }
            if (vanillaAcc.getUsage() != fastAcc.getUsage()) {
                return "accounting differs: " + vanillaAcc.getUsage() + " vs " + fastAcc.getUsage();
            }
            if (fastAcc.getDepth() != 0) {
                return "depth not restored: " + fastAcc.getDepth();
            }
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    static byte[] sample() throws IOException {
        CompoundTag inner = new CompoundTag();
        inner.putString("ascii", "minecraft:oak_leaves");
        inner.putString("unicode", "\u00e9\u00e8 \u4e16\u754c \ud83c\udf32 \u0000 end");
        inner.putString("empty", "");
        inner.put("bytes", new ByteArrayTag(new byte[]{1, -2, 3}));
        inner.put("ints", new IntArrayTag(new int[]{7, -8, 1 << 30}));
        inner.put("longs", new LongArrayTag(new long[]{-1L, Long.MAX_VALUE, 42L}));
        inner.put("b", ByteTag.valueOf((byte) -5));
        inner.put("s", ShortTag.valueOf((short) 4000));
        inner.put("f", FloatTag.valueOf(-0.0f));
        inner.put("d", DoubleTag.valueOf(Math.PI));
        inner.put("l", LongTag.valueOf(1L << 40));

        // Written with {"": x} wrappers
        ListTag mixed = new ListTag();
        mixed.add(StringTag.valueOf("minecraft:stone"));
        mixed.add(inner.copy());
        mixed.add(LongTag.valueOf(3));
        ListTag nested = new ListTag();
        nested.add(new ListTag());
        nested.add(mixed.copy());

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 5023);
        root.put("inner", inner);
        root.put("mixed", mixed);
        root.put("nested", nested);
        root.put("emptyList", new ListTag());
        root.putString("\u00fcml\u00e4ut-key", "v");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        NbtIo.write(root, out);
        byte[] written = bos.toByteArray();
        // A duplicate key, which the writer can't produce: the root's END becomes DataVersion: 1, END
        bos.reset();
        out.write(written, 0, written.length - 1);
        out.writeByte(3);
        out.writeUTF("DataVersion");
        out.writeInt(1);
        out.writeByte(0);
        return bos.toByteArray();
    }

    private static final class Holder {
        static final boolean ENABLED;
        static final ThreadLocal<FastNbtReader> READER;

        static {
            Function<List<Tag>, ListTag> lists = listFactory();
            READER = ThreadLocal.withInitial(() -> newReader(lists));
            boolean ok = false;
            if (Features.FAST_NBT) {
                FastNbtReader probe = newReader(lists);
                String problem = selfTest(probe);
                if (problem == null) {
                    ok = true;
                } else {
                    Swiftload.LOG.warn("Fast NBT reading turned off, it doesn't match this game's NBT reader "
                            + "({} dialect: {}). Minecraft reads NBT as usual, everything else stays on.",
                            probe.dialect().name().toLowerCase(), problem);
                }
            }
            ENABLED = ok;
        }

        // Null without mixins (unit tests), the reader then uses new ListTag()
        private static Function<List<Tag>, ListTag> listFactory() {
            try {
                ListTagInvoker.swiftload$new(new ArrayList<>(0));
                return ListTagInvoker::swiftload$new;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
