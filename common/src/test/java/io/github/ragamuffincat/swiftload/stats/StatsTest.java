package io.github.ragamuffincat.swiftload.stats;

import io.github.ragamuffincat.swiftload.stats.Stats.Feature;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    @Test
    void countsSamplesAndResets() throws InterruptedException {
        Stats.reportAndReset();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            pool.execute(() -> {
                for (int i = 0; i < 1000; i++) {
                    Stats.hit(Feature.FAST_NBT, 2_000);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (int i = 0; i < 7; i++) {
            Stats.fallback(Feature.FAST_NBT, new IOException("outer", new EOFException("inner " + i)));
        }
        Stats.fallback(Feature.REGION_INFLATE, "not deflate");

        assertEquals(8000, Stats.hits(Feature.FAST_NBT));
        assertEquals(7, Stats.fallbacks(Feature.FAST_NBT));
        List<String> report = Stats.reportAndReset();
        // regionInflate and its sample, fastNbt and 5 samples (capped)
        assertEquals(2 + 6, report.size(), String.join("\n", report));
        assertTrue(report.get(0).startsWith("regionInflate"), report.get(0));
        assertTrue(report.get(0).contains("0 fast, 1 fallback(s)"), report.get(0));
        assertEquals("    fallback: not deflate", report.get(1));
        String nbt = report.get(2);
        assertTrue(nbt.startsWith("fastNbt") && nbt.contains("8,000 fast, 7 fallback(s), 16.0 ms total, 2.0 us per item"), nbt);
        assertTrue(nbt.contains("threads [pool-#-thread-#]"), nbt);
        assertTrue(report.get(3).contains("java.io.IOException: outer <- java.io.EOFException: inner 0"), report.get(3));

        assertEquals(List.of(), Stats.reportAndReset(), "reset after reporting");
        Stats.fallback(Feature.FAST_NBT, "again");
        assertEquals(2, Stats.reportAndReset().size(), "sampling restarts after a reset");
    }
}
