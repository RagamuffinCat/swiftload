package io.github.ragamuffincat.swiftload.gametest;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

// Compares the map and list classes inside two equal tag trees. Mods swap them (Lithium uses fastutil maps), and
// the fast reader has to end up with whatever vanilla's reader builds in the same game.
final class Shapes {

    private static final Field TAGS, LIST;

    static {
        try {
            TAGS = CompoundTag.class.getDeclaredField("tags");
            TAGS.setAccessible(true);
            LIST = ListTag.class.getDeclaredField("list");
            LIST.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Shapes() {
    }

    static Class<?> mapClass(CompoundTag t) {
        try {
            return TAGS.get(t).getClass();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static String diff(Tag a, Tag b, String path) {
        try {
            if (a instanceof CompoundTag ca && b instanceof CompoundTag cb) {
                Map<String, Tag> ma = (Map<String, Tag>) TAGS.get(ca), mb = (Map<String, Tag>) TAGS.get(cb);
                if (ma.getClass() != mb.getClass()) {
                    return path + ": map " + ma.getClass().getName() + " vs " + mb.getClass().getName();
                }
                for (Map.Entry<String, Tag> e : ma.entrySet()) {
                    String d = diff(e.getValue(), mb.get(e.getKey()), path + "/" + e.getKey());
                    if (d != null) {
                        return d;
                    }
                }
            } else if (a instanceof ListTag la && b instanceof ListTag lb) {
                List<Tag> xa = (List<Tag>) LIST.get(la), xb = (List<Tag>) LIST.get(lb);
                if (xa.getClass() != xb.getClass()) {
                    return path + ": list " + xa.getClass().getName() + " vs " + xb.getClass().getName();
                }
                for (int i = 0; i < xa.size(); i++) {
                    String d = diff(xa.get(i), xb.get(i), path + "[" + i + "]");
                    if (d != null) {
                        return d;
                    }
                }
            } else if (a.getClass() != b.getClass()) {
                return path + ": " + a.getClass().getName() + " vs " + b.getClass().getName();
            }
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
