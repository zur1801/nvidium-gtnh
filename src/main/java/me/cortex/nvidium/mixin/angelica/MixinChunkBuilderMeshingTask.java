package me.cortex.nvidium.mixin.angelica;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;

import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizons.angelica.rendering.celeritas.AngelicaChunkBuilderMeshingTask;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.mixin.minecraft.TessellatorAccessor;
import me.cortex.nvidium.sodiumCompat.IRepackagedResult;
import me.cortex.nvidium.sodiumCompat.SodiumResultCompatibility;

@Mixin(value = AngelicaChunkBuilderMeshingTask.class, remap = false)
public class MixinChunkBuilderMeshingTask {

    @Inject(
        method = "execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",
        at = @At("TAIL"))
    private void nvidium$repackageResults(ChunkBuildContext buildContext, CancellationToken cancellationToken,
        CallbackInfoReturnable<ChunkBuildOutput> cir) {
        if (Nvidium.IS_ENABLED) {
            var result = cir.getReturnValue();
            if (result != null) {
                ((IRepackagedResult) result).nvidium$set(SodiumResultCompatibility.repackage(result));
            }
        }
    }

    @Unique
    private static final Set<String> nvidium$reportedBrokenBlocks = ConcurrentHashMap.newKeySet();

    /**
     * Some mods' block renderers need their tile entity (Forestry logs, many GregTech blocks) and throw when it is
     * missing, e.g. in chunks Bobbert restores from its cache. Angelica turns that into a crash of the whole chunk
     * builder, after which the renderer cannot recover. Skip just the offending block instead.
     */
    @WrapOperation(
        method = "renderBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderBlocks;renderBlockByRenderType(Lnet/minecraft/block/Block;III)Z",
            remap = true))
    private boolean nvidium$skipBrokenBlockRenderers(RenderBlocks renderBlocks, Block block, int x, int y, int z,
        Operation<Boolean> original, @Local(argsOnly = true) Tessellator tessellator) {
        try {
            return original.call(renderBlocks, block, x, y, z);
        } catch (RuntimeException e) {
            String name = block.getClass()
                .getName();
            if (nvidium$reportedBrokenBlocks.add(name)) {
                Nvidium.LOGGER.warn(
                    "Skipping block {} at {} {} {}: its renderer threw while meshing (further errors for this block type are not logged)",
                    name,
                    x,
                    y,
                    z,
                    e);
            }
            // Drop any partial vertices; Angelica still copies the (now empty) buffer and finishes the block
            ((TessellatorAccessor) tessellator).nvidium$reset();
            return false;
        }
    }
}
