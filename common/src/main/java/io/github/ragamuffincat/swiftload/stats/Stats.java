package io.github.ragamuffincat.swiftload.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

// Counters for debugStats (hits, fallbacks with the first few reasons, time per feature). Every call is guarded by
// Features.DEBUG_STATS, so they cost nothing when it's off.
public final class Stats {

    public static final int MAX_SAMPLES = 5;

    public enum Feature {
        REGION_INFLATE("regionInflate"),
        FAST_NBT("fastNbt"),
        FAST_PALETTE("fastPalette"),
        GZIP_FILES("gzipFiles"),
        FAST_WRITE("fastWrite"),
        // Every NbtIo.read call, fast or vanilla, so runs with features on and off can be compared
        NBT_READ_ALL("nbtRead(all)");

        final String key;
        final LongAdder hits = new LongAdder();
        final LongAdder fallbacks = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final AtomicInteger sampled = new AtomicInteger();
        final ConcurrentLinkedQueue<String> samples = new ConcurrentLinkedQueue<>();
        // Thread names with digits replaced, up to 8
        final Set<String> threads = ConcurrentHashMap.newKeySet();

        Feature(String key) {
            this.key = key;
        }
    }

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final ThreadLocal<String> THREAD_NAME =
            ThreadLocal.withInitial(() -> DIGITS.matcher(Thread.currentThread().getName()).replaceAll("#"));

    private Stats() {
    }

    public static void hit(Feature f, long nanos) {
        f.hits.increment();
        f.nanos.add(nanos);
        if (f.threads.size() < 8) {
            f.threads.add(THREAD_NAME.get());
        }
    }


    public static void fallback(Feature f, Object why) {
        f.fallbacks.increment();
        if (f.sampled.getAndIncrement() < MAX_SAMPLES) {
            f.samples.add(summarize(why));
        }
    }

    public static long hits(Feature f) {
        return f.hits.sum();
    }

    public static long fallbacks(Feature f) {
        return f.fallbacks.sum();
    }

    // One line per feature that saw traffic, then resets, so each client world session reports on its own
    public static List<String> reportAndReset() {
        List<String> lines = new ArrayList<>();
        for (Feature f : Feature.values()) {
            long hits = f.hits.sumThenReset();
            long falls = f.fallbacks.sumThenReset();
            long nanos = f.nanos.sumThenReset();
            List<String> samples = new ArrayList<>();
            for (String s; (s = f.samples.poll()) != null; ) {
                samples.add(s);
            }
            f.sampled.set(0);
            List<String> threads = new ArrayList<>(f.threads);
            f.threads.clear();
            Collections.sort(threads);
            if (hits == 0 && falls == 0) {
                continue;
            }
            String avg = hits == 0 ? "-" : String.format(Locale.ROOT, "%.1f us", nanos / 1000.0 / hits);
            lines.add(String.format(Locale.ROOT, "%-13s %,d fast, %,d fallback(s), %.1f ms total, %s per item; threads %s",
                    f.key, hits, falls, nanos / 1e6, avg, threads));
            for (String s : samples) {
                lines.add("    fallback: " + s);
            }
        }
        return lines;
    }

    static String summarize(Object why) {
        if (why instanceof Throwable t) {
            StringBuilder sb = new StringBuilder(t.toString());
            Throwable c = t.getCause();
            for (int depth = 0; c != null && c != t && depth < 3; depth++, t = c, c = c.getCause()) {
                sb.append(" <- ").append(c);
            }
            return sb.length() > 300 ? sb.substring(0, 300) + "..." : sb.toString();
        }
        return String.valueOf(why);
    }
}
