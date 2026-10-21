package io.github.ragamuffincat.swiftload.natives.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.function.BiFunction;

// The only code that calls restricted FFM methods. It ships as NativeBridge.bin (natives/build-bridge.sh) and
// NativeBinder defines it in its own class loader, so it runs in an unnamed module. The vanilla launcher and the
// Modrinth App pass --enable-native-access=ALL-UNNAMED, while NeoForge mods are named modules without native access
// and the same calls from mod code print a JDK warning there.
// Takes (symbol, descriptor, critical) triples, one handle per triple. Only java.base types cross over.
public final class NativeBridge implements BiFunction<Path, Object[], MethodHandle[]> {

    @Override
    public MethodHandle[] apply(Path library, Object[] specs) {
        if (specs.length % 3 != 0) {
            throw new IllegalArgumentException("specs must be (name, descriptor, critical) triples");
        }
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
}
