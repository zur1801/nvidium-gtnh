package me.cortex.nvidium.mixin.angelica.dh;

import java.lang.reflect.Method;

import net.coderbot.iris.pipeline.transform.DHTerrainTransformer;
import net.coderbot.iris.pipeline.transform.parameter.Parameters;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.angelica.glsm.shader.ShaderType;

/**
 * Port of upstream Iris' textured LOD support (Iris 1.11.4 / DH 3.3): DH terrain programs get the block texture id
 * and position of each LOD face, plus {@code dh_sampleTexture()} to sample DH's LOD texture atlas. Shader packs such
 * as Complementary with Euphoria Patches use these to texture LODs.
 * <p>
 * The GLSL transformer class is relocated in Angelica's release jar, so it is taken as {@code Object} and driven
 * reflectively.
 */
@Mixin(value = DHTerrainTransformer.class, remap = false)
public class MixinDHTerrainTransformer {

    @Inject(method = "transform", at = @At("TAIL"))
    private static void nvidium$addTexturedLodSupport(@Coerce Object transformer, Parameters parameters,
        int glslVersion, CallbackInfo ci) {
        if (parameters.type == ShaderType.VERTEX) {
            nvidium$addVariable(transformer, "iris_vBlockPos", "out vec3 iris_vBlockPos;");
            nvidium$addVariable(transformer, "iris_TexId", "flat out uvec2 iris_TexId;");
            nvidium$call(
                transformer,
                "injectFunction",
                "void _nvidium_dhTexInit() {" + " iris_vBlockPos = vec3(vPosition.xyz);"
                    + " iris_TexId = uvec2(irisExtra.z | (irisExtra.w << 8u), irisExtra.y);"
                    + " }");
            nvidium$call(transformer, "prependMain", "_nvidium_dhTexInit();");
        } else if (parameters.type == ShaderType.FRAGMENT) {
            nvidium$addVariable(transformer, "iris_vBlockPos", "in vec3 iris_vBlockPos;");
            nvidium$addVariable(transformer, "iris_TexId", "flat in uvec2 iris_TexId;");
            nvidium$addVariable(transformer, "dhBlockAtlas", "uniform sampler2D dhBlockAtlas;");
            nvidium$call(transformer, "injectFunction", "bool dh_hasTexture() { return iris_TexId.x != 0u; }");
            nvidium$call(
                transformer,
                "injectFunction",
                "vec2 dh_blockFaceUv() {" + " vec3 pos = fract(iris_vBlockPos);"
                    + " switch (iris_TexId.y) {"
                    + " case 0u: return vec2(pos.x, 1.0 - pos.z);" // down
                    + " case 1u: return vec2(pos.x, pos.z);" // up
                    + " case 2u: return vec2(1.0 - pos.x, 1.0 - pos.y);" // north
                    + " case 3u: return vec2(pos.x, 1.0 - pos.y);" // south
                    + " case 4u: return vec2(pos.z, 1.0 - pos.y);" // west
                    + " default: return vec2(1.0 - pos.z, 1.0 - pos.y);" // east
                    + " } }");
            nvidium$call(
                transformer,
                "injectFunction",
                "vec4 dh_sampleTexture() {" + " ivec2 atlasSize = textureSize(dhBlockAtlas, 0);"
                    + " vec2 tileOrigin = vec2(float(iris_TexId.x % 256u), float(iris_TexId.x / 256u)) * 16.0;"
                    + " vec2 uv = (tileOrigin + dh_blockFaceUv() * 16.0) / vec2(atlasSize);"
                    + " return texture(dhBlockAtlas, uv);"
                    + " }");
        }
    }

    @Unique
    private static void nvidium$addVariable(Object transformer, String name, String declaration) {
        if (!(Boolean) nvidium$call(transformer, "hasVariable", name)) {
            nvidium$call(transformer, "injectVariable", declaration);
        }
    }

    @Unique
    private static Object nvidium$call(Object transformer, String method, String argument) {
        try {
            Method m = transformer.getClass()
                .getMethod(method, String.class);
            return m.invoke(transformer, argument);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Angelica's shader transformer has no " + method + "(String)", e);
        }
    }
}
