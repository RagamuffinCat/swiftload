package io.github.ragamuffincat.swiftload;

import io.github.ragamuffincat.swiftload.config.SwiftloadConfig;
import io.github.ragamuffincat.swiftload.config.SwiftloadConfig.Key;
import io.github.ragamuffincat.swiftload.natives.LibDeflate;

// Fixed at class init so the JIT folds every check. Hooks read these, never the config.
public final class Features {

    private static final SwiftloadConfig CONFIG = Swiftload.config();

    public static final boolean REGION_INFLATE = CONFIG.get(Key.regionInflate) && LibDeflate.isAvailable();
    // Needs an inflated array, so regionInflate or gzipFiles
    public static final boolean FAST_NBT = CONFIG.get(Key.fastNbt);
    public static final boolean FAST_PALETTE = CONFIG.get(Key.fastPalette);
    public static final boolean GZIP_FILES = CONFIG.get(Key.gzipFiles) && LibDeflate.isAvailable();
    public static final boolean FAST_WRITE = CONFIG.get(Key.fastWrite) && LibDeflate.isAvailable();
    public static final boolean DEBUG_STATS = CONFIG.get(Key.debugStats);

    private Features() {
    }
}
