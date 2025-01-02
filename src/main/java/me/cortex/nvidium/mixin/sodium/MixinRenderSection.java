package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.sodiumCompat.IRenderSectionExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = RenderSection.class, remap = false)
public class MixinRenderSection implements IRenderSectionExtension {
    @Unique private volatile boolean isEnqueued;
    @Unique private volatile boolean isSeen;

    @Override
    public boolean nVidium$isSubmittedRebuild() {
        return isEnqueued;
    }

    @Override
    public void nVidium$isSubmittedRebuild(boolean state) {
        isEnqueued = state;
    }

    @Override
    public boolean nVidium$isSeen() {
        return isSeen;
    }

    @Override
    public void nVidium$isSeen(boolean state) {
        isSeen = state;
    }
}
