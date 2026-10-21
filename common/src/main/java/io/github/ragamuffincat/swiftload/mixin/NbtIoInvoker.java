package io.github.ragamuffincat.swiftload.mixin;

import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// NbtIo's own gzip streams, for the fallbacks
@Mixin(NbtIo.class)
public interface NbtIoInvoker {

    @Invoker("createDecompressorStream")
    static DataInputStream swiftload$createDecompressorStream(InputStream in) {
        throw new AssertionError("mixin not applied");
    }

    @Invoker("createCompressorStream")
    static DataOutputStream swiftload$createCompressorStream(OutputStream out) {
        throw new AssertionError("mixin not applied");
    }
}
