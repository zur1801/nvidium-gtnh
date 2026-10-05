package me.cortex.nvidium.mixin.minecraft;

import net.minecraft.client.renderer.Tessellator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Tessellator.class)
public interface TessellatorAccessor {

    @Invoker("reset")
    void nvidium$reset();
}
