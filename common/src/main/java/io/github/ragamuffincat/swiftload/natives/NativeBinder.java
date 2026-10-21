package io.github.ragamuffincat.swiftload.natives;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.function.BiFunction;

// Binds the downcall handles through NativeBridge, which runs in an unnamed module (see its source for why).
// The direct route works everywhere too, but NeoForge then prints a restricted method warning.
final class NativeBinder {

    static final String BRIDGE_RESOURCE = "io/github/ragamuffincat/swiftload/natives/NativeBridge.bin";
    static final String BRIDGE_CLASS = "io.github.ragamuffincat.swiftload.natives.bridge.NativeBridge";

    enum Route {
        BRIDGE, DIRECT
    }

    record Bound(MethodHandle[] handles, Route route) {
    }

    private NativeBinder() {
    }

    static Bound bind(Path library, Object[] specs, boolean allowBridge) {
        if (allowBridge) {
            try {
                return new Bound(viaBridge(library, specs), Route.BRIDGE);
            } catch (Throwable ignored) {
                // The startup line names the route that was used
            }
        }
        return new Bound(direct(library, specs), Route.DIRECT);
    }

    @SuppressWarnings("unchecked")
    private static MethodHandle[] viaBridge(Path library, Object[] specs) throws Exception {
        byte[] bytes;
        try (InputStream in = NativeBinder.class.getClassLoader().getResourceAsStream(BRIDGE_RESOURCE)) {
            if (in == null) {
                throw new IOException("bridge resource missing: " + BRIDGE_RESOURCE);
            }
            bytes = in.readAllBytes();
        }
        BridgeLoader loader = new BridgeLoader(NativeBinder.class.getClassLoader());
        Class<?> bridge = loader.define(BRIDGE_CLASS, bytes);
        BiFunction<Path, Object[], MethodHandle[]> fn =
                (BiFunction<Path, Object[], MethodHandle[]>) bridge.getDeclaredConstructor().newInstance();
        return fn.apply(library, specs);
    }

    private static MethodHandle[] direct(Path library, Object[] specs) {
        SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
        Linker linker = Linker.nativeLinker();
        MethodHandle[] handles = new MethodHandle[specs.length / 3];
        for (int i = 0; i < handles.length; i++) {
            String name = (String) specs[3 * i];
            FunctionDescriptor descriptor = (FunctionDescriptor) specs[3 * i + 1];
            boolean critical = (Boolean) specs[3 * i + 2];
            MemorySegment symbol = lookup.find(name)
                    .orElseThrow(() -> new IllegalStateException("symbol not found: " + name));
            handles[i] = critical
                    ? linker.downcallHandle(symbol, descriptor, Linker.Option.critical(true))
                    : linker.downcallHandle(symbol, descriptor);
        }
        return handles;
    }

    // Only owns the bridge class, and with it the unnamed module
    private static final class BridgeLoader extends ClassLoader {

        BridgeLoader(ClassLoader parent) {
            super("swiftload-native-bridge", parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
