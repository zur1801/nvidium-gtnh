package me.cortex.nvidium.mixin.celeritas;

import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;

@Mixin(value = SimpleWorldRenderer.class, remap = false)
public abstract class MixinSimpleWorldRenderer implements INvidiumWorldRendererGetter {

    @Shadow
    public abstract RenderSectionManager getRenderSectionManager();

    @Override
    public NvidiumWorldRenderer nvidium$getRenderer() {
        RenderSectionManager manager = getRenderSectionManager();
        if (Nvidium.IS_ENABLED && manager != null) {
            return ((INvidiumWorldRendererGetter) manager).nvidium$getRenderer();
        } else {
            return null;
        }
    }
}
