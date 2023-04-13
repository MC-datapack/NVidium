package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderPipeline;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkCameraContext;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Shadow private Frustum frustum;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(SodiumWorldRenderer worldRenderer, ClientWorld world, int renderDistance, CommandList commandList, CallbackInfo ci) {
        if (Nvidium.pipeline != null)
            throw new IllegalStateException("Cannot have multiple pipelines");
        Nvidium.pipeline = new RenderPipeline();
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void destroy(CallbackInfo ci) {
        if (Nvidium.pipeline == null)
            throw new IllegalStateException("Pipeline already destroyed");
        Nvidium.pipeline.delete();
        Nvidium.pipeline = null;
    }

    @Inject(method = "unloadSection", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;delete()V", shift = At.Shift.BEFORE), locals = LocalCapture.CAPTURE_FAILHARD)
    private void deleteSection(int x, int y, int z, CallbackInfoReturnable<Boolean> cir, RenderSection chunk, RenderRegion region) {
        //Nvidium.pipeline.sectionManager.deleteSection(chunk);
    }

    @Overwrite
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z) {
        if (pass == DefaultTerrainRenderPasses.SOLID) {
            Nvidium.pipeline.renderFrame(frustum, matrices, new ChunkCameraContext(x, y, z));
        } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
            Nvidium.pipeline.renderTranslucent();
        }
    }
}