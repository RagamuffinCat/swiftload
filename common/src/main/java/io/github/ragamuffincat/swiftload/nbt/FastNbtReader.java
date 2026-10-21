package io.github.ragamuffincat.swiftload.nbt;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtFormatException;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.EOFException;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

// NbtIo.read over a byte array, in one pass. Charges the real NbtAccounter with vanilla's amounts in vanilla's order,
// uses the same tag factories and unwraps {"": x} list elements like ListTag.addAndUnwrap. Throws on anything vanilla
// would reject, and the caller lets vanilla read the same bytes so the error is vanilla's.
// One per thread, it caches compound keys.
public final class FastNbtReader {

    public enum Dialect {
        // Root name skipped without accounting; keys cost 28 + 2 per char, strings 36 + 2 per char
        VANILLA,
        // NeoForge 26.3: root costs 1 and its name goes through NbtAccounter.readUTF plus 4; each compound entry
        // costs 2 for its type, keys readUTF plus 4; strings 36 + readUTF
        NEOFORGE
    }

    static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4, TAG_FLOAT = 5,
            TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9, TAG_COMPOUND = 10,
            TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    private static final VarHandle I16 = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle I32 = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle I64 = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    // Only where byte order doesn't matter (ASCII check, cache words)
    private static final VarHandle I64N = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private static final int KC_BITS = 9, KC_SIZE = 1 << KC_BITS, KC_MAXLEN = 32;

    // Static final so the JIT can inline it. Through the instance field every string paid an indirect call, NeoForge
    // chunks went from ~50 to ~44 us when this changed
    private static final MethodHandle GAME_ACCOUNT_UTF = FastNbt.neoForgeAccountUtf();

    private final Dialect dialect;
    // (NbtAccounter, String) -> String, NeoForge only
    private final MethodHandle accountUtf;
    // new ListTag(new ArrayList<>(n)) like vanilla; null falls back to new ListTag()
    private final Function<List<Tag>, ListTag> listFactory;

    private byte[] buf;
    private int pos;
    private int limit;
    private NbtAccounter acc;

    // Direct-mapped key cache: raw MUTF-8 bytes (up to 32, zero padded into 4 words) to String
    private final String[] kcStr = new String[KC_SIZE];
    private final int[] kcLen = new int[KC_SIZE];
    private final long[] kcW = new long[KC_SIZE * 4];

    public FastNbtReader(Dialect dialect, MethodHandle accountUtf, Function<List<Tag>, ListTag> listFactory) {
        this.dialect = Objects.requireNonNull(dialect);
        if (dialect == Dialect.NEOFORGE) {
            Objects.requireNonNull(accountUtf, "NeoForge dialect needs readUTF");
        }
        this.accountUtf = accountUtf;
        this.listFactory = listFactory;
    }

    public Dialect dialect() {
        return dialect;
    }

    // Where the last readRoot stopped, which is where vanilla's stream would be
    public int position() {
        return pos;
    }

    // Like vanilla, bytes after the root compound are ignored
    public CompoundTag readRoot(byte[] b, int off, int len, NbtAccounter accounter) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        this.buf = b;
        this.pos = off;
        this.limit = off + len;
        this.acc = accounter;
        try {
            need(1);
            int type = buf[pos++];
            if (dialect == Dialect.NEOFORGE) {
                acc.accountBytes(1L);
            }
            if (type != TAG_COMPOUND) {
                throw new IOException("Root tag must be a named compound tag");
            }
            if (dialect == Dialect.VANILLA) {
                // readUnsignedShort, then skipBytes skips whatever is there
                need(2);
                int nameLength = u16();
                pos += Math.min(nameLength, limit - pos);
            } else {
                accountUtf(readUtf());
                acc.accountBytes(4L);
            }
            return loadCompound();
        } finally {
            this.buf = null;
            this.acc = null;
        }
    }

    private Tag load(int type) throws IOException {
        switch (type) {
            case TAG_COMPOUND:
                return loadCompound();
            case TAG_STRING: {
                acc.accountBytes(36L);
                String s = readUtf();
                if (dialect == Dialect.VANILLA) {
                    acc.accountBytes(2L, s.length());
                } else {
                    accountUtf(s);
                }
                return StringTag.valueOf(s);
            }
            case TAG_LIST:
                return loadList();
            case TAG_LONG_ARRAY: {
                acc.accountBytes(24L);
                int n = i32();
                acc.accountBytes(8L, n);
                if (n < 0 || (long) n * 8 > limit - pos) {
                    throw new EOFException();
                }
                long[] a = new long[n];
                int p = pos;
                for (int i = 0; i < n; i++, p += 8) {
                    a[i] = (long) I64.get(buf, p);
                }
                pos = p;
                return new LongArrayTag(a);
            }
            case TAG_BYTE:
                acc.accountBytes(9L);
                need(1);
                return ByteTag.valueOf(buf[pos++]);
            case TAG_INT:
                acc.accountBytes(12L);
                return IntTag.valueOf(i32());
            case TAG_BYTE_ARRAY: {
                acc.accountBytes(24L);
                int n = i32();
                acc.accountBytes(1L, n);
                if (n < 0 || n > limit - pos) {
                    throw new EOFException();
                }
                byte[] a = Arrays.copyOfRange(buf, pos, pos + n);
                pos += n;
                return new ByteArrayTag(a);
            }
            case TAG_LONG:
                acc.accountBytes(16L);
                return LongTag.valueOf(i64());
            case TAG_SHORT: {
                acc.accountBytes(10L);
                need(2);
                short v = (short) I16.get(buf, pos);
                pos += 2;
                return ShortTag.valueOf(v);
            }
            case TAG_FLOAT:
                acc.accountBytes(12L);
                return FloatTag.valueOf(Float.intBitsToFloat(i32()));
            case TAG_DOUBLE:
                acc.accountBytes(16L);
                return DoubleTag.valueOf(Double.longBitsToDouble(i64()));
            case TAG_INT_ARRAY: {
                acc.accountBytes(24L);
                int n = i32();
                acc.accountBytes(4L, n);
                if (n < 0 || (long) n * 4 > limit - pos) {
                    throw new EOFException();
                }
                int[] a = new int[n];
                int p = pos;
                for (int i = 0; i < n; i++, p += 4) {
                    a[i] = (int) I32.get(buf, p);
                }
                pos = p;
                return new IntArrayTag(a);
            }
            case TAG_END:
                acc.accountBytes(8L);
                return EndTag.INSTANCE;
            default:
                throw new IOException("Invalid tag id: " + type);
        }
    }

    private CompoundTag loadCompound() throws IOException {
        acc.pushDepth();
        try {
            acc.accountBytes(48L);
            // new CompoundTag() like vanilla's loadCompound, Lithium swaps the map type in this constructor
            CompoundTag tag = new CompoundTag();
            while (true) {
                if (dialect == Dialect.NEOFORGE) {
                    acc.accountBytes(2L);
                }
                need(1);
                int id = buf[pos++];
                if (id == TAG_END) {
                    return tag;
                }
                String key = readKey();
                if (dialect == Dialect.VANILLA) {
                    acc.accountBytes(28L);
                    acc.accountBytes(2L, key.length());
                } else {
                    accountUtf(key);
                    acc.accountBytes(4L);
                }
                Tag value = load(id);
                if (tag.put(key, value) == null) {
                    acc.accountBytes(36L);
                }
            }
        } finally {
            acc.popDepth();
        }
    }

    private ListTag loadList() throws IOException {
        acc.pushDepth();
        try {
            acc.accountBytes(36L);
            need(1);
            int elementType = buf[pos++];
            int n = i32();
            if (n < 0) {
                throw new NbtFormatException("ListTag length cannot be negative: " + n);
            }
            if (elementType == TAG_END && n > 0) {
                throw new NbtFormatException("Missing type on ListTag");
            }
            acc.accountBytes(4L, n);
            // Every element takes at least a byte. Vanilla would allocate the list first and then fail, the fallback
            // does that.
            if (n > limit - pos) {
                throw new EOFException();
            }
            ListTag list = listFactory != null ? listFactory.apply(new ArrayList<>(n)) : new ListTag();
            for (int i = 0; i < n; i++) {
                list.addAndUnwrap(load(elementType));
            }
            return list;
        } finally {
            acc.popDepth();
        }
    }

    private void need(int n) throws EOFException {
        if (n > limit - pos) {
            throw new EOFException();
        }
    }

    private int u16() {
        int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    private int i32() throws EOFException {
        need(4);
        int v = (int) I32.get(buf, pos);
        pos += 4;
        return v;
    }

    private long i64() throws EOFException {
        need(8);
        long v = (long) I64.get(buf, pos);
        pos += 8;
        return v;
    }

    private void accountUtf(String s) throws IOException {
        try {
            if (accountUtf == GAME_ACCOUNT_UTF) {
                String ignored = (String) GAME_ACCOUNT_UTF.invokeExact(acc, s);
                return;
            }
            String ignored = (String) accountUtf.invokeExact(acc, s);
        } catch (RuntimeException | Error | IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private String readUtf() throws IOException {
        need(2);
        int len = u16();
        need(len);
        int p = pos;
        pos += len;
        return decodeMutf8(buf, p, len);
    }

    private long word(int p, int len, int off) {
        int rem = len - off;
        if (rem >= 8) {
            return (long) I64N.get(buf, p + off);
        }
        long w = 0;
        for (int i = 0; i < rem; i++) {
            w |= (buf[p + off + i] & 0xFFL) << (8 * i);
        }
        return w;
    }

    // readUtf, deduplicated through the key cache
    private String readKey() throws IOException {
        need(2);
        int len = u16();
        need(len);
        int p = pos;
        pos += len;
        if (len > KC_MAXLEN) {
            return decodeMutf8(buf, p, len);
        }
        long w0 = word(p, len, 0);
        long w1 = len > 8 ? word(p, len, 8) : 0L;
        long w2 = len > 16 ? word(p, len, 16) : 0L;
        long w3 = len > 24 ? word(p, len, 24) : 0L;
        long h = w0 * 0x9E3779B97F4A7C15L + w1 * 0xC2B2AE3D27D4EB4FL + w2 * 0x165667B19E3779F9L
                + w3 * 0xD6E8FEB86659FD93L + len;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        int slot = (int) (h >>> (64 - KC_BITS));
        int wi = slot << 2;
        String s = kcStr[slot];
        if (s != null && kcLen[slot] == len && kcW[wi] == w0 && kcW[wi + 1] == w1 && kcW[wi + 2] == w2 && kcW[wi + 3] == w3) {
            return s;
        }
        // Throws on malformed input, before anything is cached
        s = decodeMutf8(buf, p, len);
        kcStr[slot] = s;
        kcLen[slot] = len;
        kcW[wi] = w0;
        kcW[wi + 1] = w1;
        kcW[wi + 2] = w2;
        kcW[wi + 3] = w3;
        return s;
    }

    // DataInputStream.readUTF, rejecting the same inputs. Pure ASCII becomes a Latin-1 string in one copy.
    static String decodeMutf8(byte[] b, int p, int len) throws UTFDataFormatException {
        int i = 0;
        while (i + 8 <= len) {
            if (((long) I64N.get(b, p + i) & 0x8080808080808080L) != 0L) {
                break;
            }
            i += 8;
        }
        while (i < len && b[p + i] >= 0) {
            i++;
        }
        if (i == len) {
            return new String(b, p, len, StandardCharsets.ISO_8859_1);
        }
        return decodeMutf8Slow(b, p, len, i);
    }

    // readUTF's decoding loop, after the ASCII prefix
    private static String decodeMutf8Slow(byte[] b, int p, int utflen, int asciiPrefix) throws UTFDataFormatException {
        char[] ca = new char[utflen];
        int cc = 0;
        for (; cc < asciiPrefix; cc++) {
            ca[cc] = (char) b[p + cc];
        }
        int count = asciiPrefix;
        while (count < utflen) {
            int c = b[p + count] & 0xFF;
            switch (c >> 4) {
                case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                    count++;
                    ca[cc++] = (char) c;
                }
                case 12, 13 -> {
                    count += 2;
                    if (count > utflen) {
                        throw new UTFDataFormatException("malformed input: partial character at end");
                    }
                    int c2 = b[p + count - 1];
                    if ((c2 & 0xC0) != 0x80) {
                        throw new UTFDataFormatException("malformed input around byte " + count);
                    }
                    ca[cc++] = (char) (((c & 0x1F) << 6) | (c2 & 0x3F));
                }
                case 14 -> {
                    count += 3;
                    if (count > utflen) {
                        throw new UTFDataFormatException("malformed input: partial character at end");
                    }
                    int c2 = b[p + count - 2];
                    int c3 = b[p + count - 1];
                    if (((c2 & 0xC0) != 0x80) || ((c3 & 0xC0) != 0x80)) {
                        throw new UTFDataFormatException("malformed input around byte " + (count - 1));
                    }
                    ca[cc++] = (char) (((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F));
                }
                default -> throw new UTFDataFormatException("malformed input around byte " + count);
            }
        }
        return new String(ca, 0, cc);
    }
}
