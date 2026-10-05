package me.cortex.nvidium.mixin.celeritas;

import java.util.ArrayList;
import java.util.Collection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.embeddedt.embeddium.impl.render.chunk.region.RenderRegionManager;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererSetter;

/**
 * Celeritas 2.5.11 (Angelica 2.2.x) does its own asynchronous occlusion search, so Nvidium only needs to take over
 * geometry upload (see {@link MixinRenderRegionManager}) and drawing. Section scheduling, render lists, block
 * entities and animated sprites are left to Celeritas.
 */
@Mixin(value = RenderSectionManager.class, remap = false, priority = 1500) // Ensure priority over Iris so it doesn't
                                                                           // hijack our ChunkVertexFormat
public abstract class MixinRenderSectionManager implements INvidiumWorldRendererGetter {

    @Shadow
    @Final
    private RenderRegionManager regions;

    @Unique
    public NvidiumWorldRenderer nvidium$renderer;

    @Unique
    private Viewport nvidium$viewport;

    @Unique
    public RenderRegionManager nvidium$getRegions() {
        return regions;
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void nvidium$destroy(CallbackInfo ci) {
        if (nvidium$renderer != null) {
            ((INvidiumWorldRendererSetter) regions).nvidium$setWorldRenderer(null);
            nvidium$renderer.delete();
            nvidium$renderer = null;
        }
    }

    @Redirect(
        method = "onSectionRemoved",
        at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/chunk/RenderSection;delete()V"))
    private void nvidium$deleteSection(RenderSection section) {
        if (nvidium$renderer != null) {
            // region_keep_distance == 32 means "vanilla": geometry is dropped as soon as the chunk unloads.
            // Otherwise RenderPipeline evicts whole regions once they fall outside the keep distance.
            if (Nvidium.config.region_keep_distance == 32
                || Nvidium.config.region_keep_distance <= Nvidium.Compat.getEffectiveRenderDistance()) {
                nvidium$renderer.deleteSection(section);
            }
        }
        section.delete();
    }

    /**
     * Celeritas marks sections it sees as empty without building them, so no build result reaches Nvidium. The mesh
     * cache still has to forget them, or stale geometry would be restored on the next join.
     */
    @Inject(
        method = "onSectionAdded",
        at = @At(
            value = "INVOKE",
            target = "Lorg/embeddedt/embeddium/impl/render/chunk/RenderSectionManager;updateSectionInfo(Lorg/embeddedt/embeddium/impl/render/chunk/RenderSection;Lorg/embeddedt/embeddium/impl/render/chunk/data/BuiltRenderSectionData;)Z"))
    private void nvidium$onEmptySectionAdded(int x, int y, int z, CallbackInfo ci) {
        if (nvidium$renderer == null) return;
        WorldClient world = Minecraft.getMinecraft().theWorld;
        // An unloaded column also reads as empty; only trust the answer for real chunks
        if (world != null && !world.getChunkFromChunkCoords(x, z)
            .isEmpty()) {
            nvidium$renderer.onSectionEmpty(x, y, z);
        }
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void nvidium$trackViewport(Viewport positionedViewport, int frame, boolean spectator, CallbackInfo ci) {
        this.nvidium$viewport = positionedViewport;
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void nvidium$renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass,
        CameraTransform occlusionCamera, CameraTransform camera, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && nvidium$renderer != null) {
            ci.cancel();
            if (nvidium$viewport == null) {
                // Angelica skips the first terrain updates until the camera section exists
                return;
            }
            pass.startDrawing();
            if (pass == Nvidium.Compat.getSolidPass()) {
                nvidium$renderer.renderFrame(nvidium$viewport, matrices, camera.x, camera.y, camera.z);
            } else if (pass == Nvidium.Compat.getTranslucentPass()) {
                nvidium$renderer.renderTranslucent();
            }
            nvidium$renderer.restoreGlState();
            pass.endDrawing();
        }
    }

    /**
     * Celeritas only schedules translucency re-sorts for regions that hold vanilla mesh data for the pass. Nvidium
     * owns the geometry, so the vanilla regions stay empty; report them as populated so sort tasks still run.
     */
    @Redirect(
        method = "scheduleTranslucencyUpdates",
        at = @At(
            value = "INVOKE",
            target = "Lorg/embeddedt/embeddium/impl/render/chunk/region/RenderRegion;hasSectionsInPass(Lorg/embeddedt/embeddium/impl/render/chunk/terrain/TerrainRenderPass;)Z"))
    private boolean nvidium$regionHasTranslucentSections(RenderRegion region, TerrainRenderPass pass) {
        if (nvidium$renderer != null) {
            return true;
        }
        return region.hasSectionsInPass(pass);
    }

    @Inject(method = "getDebugStrings", at = @At("RETURN"), cancellable = true)
    private void nvidium$addDebugStrings(CallbackInfoReturnable<Collection<String>> cir) {
        if (nvidium$renderer != null) {
            var debugStrings = new ArrayList<String>();
            nvidium$renderer.addDebugInfo(debugStrings);
            debugStrings.addAll(cir.getReturnValue());
            cir.setReturnValue(debugStrings);
        }
    }

    @Override
    public NvidiumWorldRenderer nvidium$getRenderer() {
        return nvidium$renderer;
    }
}
