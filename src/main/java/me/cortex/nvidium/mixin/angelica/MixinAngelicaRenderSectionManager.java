package me.cortex.nvidium.mixin.angelica;

import net.minecraft.client.multiplayer.WorldClient;

import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.angelica.rendering.celeritas.AngelicaRenderSectionManager;
import com.gtnewhorizons.angelica.rendering.celeritas.threading.ChunkTaskProvider;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.mixin.celeritas.MixinRenderSectionManager;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererSetter;

@Mixin(value = AngelicaRenderSectionManager.class, remap = false)
public abstract class MixinAngelicaRenderSectionManager extends MixinRenderSectionManager {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void nvidium$init(RenderPassConfiguration<?> configuration, WorldClient world, int renderDistance,
        CommandList commandList, int minSection, int maxSection, int requestedThreads, ChunkTaskProvider taskProvider,
        CallbackInfo ci) {
        Nvidium.updateNvidiumIsEnabled();
        if (Nvidium.IS_ENABLED) {
            if (nvidium$renderer != null) throw new IllegalStateException("Cannot have multiple world renderers");
            try {
                nvidium$renderer = new NvidiumWorldRenderer(world);
            } catch (Throwable t) {
                // Angelica rebuilds the renderer when construction fails. Without this, every retry hits the same
                // error and leaks another set of chunk builder threads until the JVM runs out of native memory.
                Nvidium.LOGGER.error("Failed to start the Nvidium renderer, disabling Nvidium for this session", t);
                Nvidium.FORCE_DISABLE = true;
                Nvidium.IS_ENABLED = false;
                ((RenderSectionManager) (Object) this).destroy();
                throw t;
            }
            ((INvidiumWorldRendererSetter) nvidium$getRegions()).nvidium$setWorldRenderer(nvidium$renderer);
        }
    }
}
