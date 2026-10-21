package io.github.ragamuffincat.swiftload.mixin;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

// The package-private constructor, so loaded lists get the same backing list as vanilla's
@Mixin(ListTag.class)
public interface ListTagInvoker {

    @Invoker("<init>")
    static ListTag swiftload$new(List<Tag> list) {
        throw new AssertionError("mixin not applied");
    }
}
