package io.github.ragamuffincat.swiftload.testdata;

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
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// RealChunkNbt's plain Java model to Minecraft tags, and tags to bytes
public final class Tags {

    private Tags() {
    }

    @SuppressWarnings("unchecked")
    public static Tag of(Object o) {
        if (o instanceof Map<?, ?> m) {
            CompoundTag c = new CompoundTag();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                c.put(e.getKey(), of(e.getValue()));
            }
            return c;
        }
        if (o instanceof List<?> l) {
            ListTag t = new ListTag();
            for (Object e : l) {
                t.add(of(e));
            }
            return t;
        }
        if (o instanceof String s) {
            return StringTag.valueOf(s);
        }
        if (o instanceof Byte b) {
            return ByteTag.valueOf(b);
        }
        if (o instanceof Short s) {
            return ShortTag.valueOf(s);
        }
        if (o instanceof Integer i) {
            return IntTag.valueOf(i);
        }
        if (o instanceof Long l) {
            return LongTag.valueOf(l);
        }
        if (o instanceof Float f) {
            return FloatTag.valueOf(f);
        }
        if (o instanceof Double d) {
            return DoubleTag.valueOf(d);
        }
        if (o instanceof byte[] a) {
            return new ByteArrayTag(a);
        }
        if (o instanceof int[] a) {
            return new IntArrayTag(a);
        }
        if (o instanceof long[] a) {
            return new LongArrayTag(a);
        }
        throw new IllegalArgumentException("not an NBT model value: " + o);
    }

    // As NbtIo.write: root type, empty name, payload
    public static byte[] bytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bos));
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // count chunks in the 26.3 palette format, seed 0xC0FFEE
    public static List<byte[]> chunkCorpus(int count) {
        RealChunkNbt gen = new RealChunkNbt(0xC0FFEE, true);
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(bytes((CompoundTag) of(gen.buildChunk(i % 8, i / 8))));
        }
        return out;
    }
}
