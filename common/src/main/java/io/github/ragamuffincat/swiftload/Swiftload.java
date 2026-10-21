package io.github.ragamuffincat.swiftload;

import io.github.ragamuffincat.swiftload.config.SwiftloadConfig;
import io.github.ragamuffincat.swiftload.natives.LibDeflate;
import io.github.ragamuffincat.swiftload.platform.Services;
import io.github.ragamuffincat.swiftload.stats.Stats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public final class Swiftload {

    public static final String MOD_ID = "swiftload";
    public static final String MOD_NAME = "Swiftload";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Swiftload() {
    }

    public static void init() {
        SwiftloadConfig config = config();
        List<String> changed = config.nonDefaults();
        String settings = changed.isEmpty() ? "" : "; config: " + String.join(", ", changed);
        // Loads the natives now, so the status line is logged at startup and not on the first chunk load
        if (LibDeflate.isAvailable() || !config.get(SwiftloadConfig.Key.natives)) {
            LOG.info("{} on {}: {}{}", MOD_NAME, platformName(), LibDeflate.status(), settings);
        } else {
            LOG.warn("{} on {}: {}, using java.util.zip (same results, just slower){}",
                    MOD_NAME, platformName(), LibDeflate.status(), settings);
        }
    }

    public static void printStats() {
        List<String> lines = Stats.reportAndReset();
        if (lines.isEmpty()) {
            LOG.info("{} stats: no chunk or file loads went through Swiftload since the last report", MOD_NAME);
        } else {
            LOG.info("{} stats since the last report:\n  {}", MOD_NAME, String.join("\n  ", lines));
        }
    }

    // Mixins can run before init(), so whichever comes first loads it
    public static SwiftloadConfig config() {
        return Environment.CONFIG;
    }

    private static String platformName() {
        try {
            return Services.PLATFORM.getPlatformName();
        } catch (Throwable t) {
            return "an unknown loader";
        }
    }

    private static final class Environment {
        static final SwiftloadConfig CONFIG;

        static {
            Path configDir = null;
            Path gameDir = null;
            try {
                configDir = Services.PLATFORM.getConfigDirectory();
                gameDir = Services.PLATFORM.getGameDirectory();
            } catch (Throwable ignored) {
                // No loader in plain unit tests, so JVM properties only and natives in the temp dir
            }
            CONFIG = configDir != null
                    ? SwiftloadConfig.load(configDir.resolve(SwiftloadConfig.FILE_NAME), System::getProperty,
                            msg -> LOG.warn(msg))
                    : SwiftloadConfig.fromSystemProperties(System::getProperty, msg -> LOG.warn(msg));

            boolean bridge = !"false".equalsIgnoreCase(System.getProperty("swiftload.natives.bridge"));
            // Not /tmp: some servers mount it noexec, and Windows can't delete a DLL in use so copies would pile up
            Path natives = gameDir != null ? gameDir.resolve(".swiftload").resolve("natives") : null;
            LibDeflate.configure(natives, bridge, CONFIG.get(SwiftloadConfig.Key.natives));
        }
    }
}
