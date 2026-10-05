package me.cortex.nvidium.mixin.angelica.dh;

import net.coderbot.iris.compat.dh.IrisLodRenderProgram;

import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.objects.DhApiResult;

/**
 * Binds Distant Horizons' LOD texture atlas for the {@code dhBlockAtlas} sampler declared by
 * {@link MixinDHTerrainTransformer}, matching upstream Iris (texture unit 0).
 */
@Mixin(value = IrisLodRenderProgram.class, remap = false)
public abstract class MixinIrisLodRenderProgram {

    @Shadow
    public abstract int tryGetUniformLocation2(CharSequence name);

    @Unique
    private int nvidium$dhBlockAtlasUniform = -2;

    @Unique
    private boolean nvidium$atlasWarned;

    @Inject(method = "fillUniformData", at = @At("TAIL"))
    private void nvidium$bindLodAtlas(Matrix4fc projection, Matrix4fc modelView, int worldYOffset, float partialTicks,
        CallbackInfo ci) {
        if (nvidium$dhBlockAtlasUniform == -2) {
            nvidium$dhBlockAtlasUniform = tryGetUniformLocation2("dhBlockAtlas");
        }
        if (nvidium$dhBlockAtlasUniform == -1) {
            // The pack does not sample LOD textures
            return;
        }
        if (DhApi.Delayed.renderProxy == null) return;
        DhApiResult<Integer> atlas = DhApi.Delayed.renderProxy.getDhBlockRatioAtlasTextureGlId();
        if (atlas.success && atlas.payload != null) {
            GLStateManager.glUniform1i(nvidium$dhBlockAtlasUniform, 0);
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, atlas.payload);
        } else if (!nvidium$atlasWarned) {
            nvidium$atlasWarned = true;
            me.cortex.nvidium.Nvidium.LOGGER
                .warn("Distant Horizons did not provide a LOD texture atlas, LODs stay untextured: {}", atlas.message);
        }
    }
}
