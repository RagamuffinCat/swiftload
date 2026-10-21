package io.github.ragamuffincat.swiftload.platform;

import java.util.ServiceLoader;

public final class Services {

    public static final PlatformHelper PLATFORM = load(PlatformHelper.class);

    private Services() {
    }

    private static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No service implementation for " + type.getName()));
    }
}
