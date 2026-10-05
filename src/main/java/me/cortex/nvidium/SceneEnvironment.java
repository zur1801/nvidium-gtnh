package me.cortex.nvidium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.FogStateStack;

import cpw.mods.fml.common.Loader;
import me.cortex.nvidium.mixin.minecraft.MinecraftAccessor;

/**
 * Per-frame lighting and fog inputs for the terrain shaders: sun/moon direction and colour, and fog that follows
 * Minecraft's own fog colour but extends to Distant Horizons' range so real terrain hands over to the LODs.
 */
public final class SceneEnvironment {

    public final Vector4f lightDirection = new Vector4f();
    public final Vector4f lightColour = new Vector4f(1, 1, 1, 1);
    public final Vector4f fogColour = new Vector4f();
    public float fogStart;
    public float fogEnd;

    private static final Vector3f SUN_COLOUR = new Vector3f(1.0f, 0.96f, 0.9f);
    private static final Vector3f SUNSET_COLOUR = new Vector3f(1.0f, 0.62f, 0.35f);
    private static final Vector3f MOON_COLOUR = new Vector3f(0.75f, 0.85f, 1.0f);

    private static final boolean DISTANT_HORIZONS = Loader.isModLoaded("distanthorizons");

    public void update() {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        float partialTicks = ((MinecraftAccessor) mc).nvidium$getTimer().renderPartialTicks;

        updateLight(world, partialTicks);
        updateFog(mc);
    }

    private void updateLight(WorldClient world, float partialTicks) {
        if (world == null || world.provider.hasNoSky || !Nvidium.config.sun_lighting) {
            lightDirection.set(0, 1, 0, 0);
            return;
        }

        // RenderGlobal#renderSky rotates the sun by -90 degrees around Y, then by the celestial angle around X
        double angle = world.getCelestialAngle(partialTicks) * Math.PI * 2.0;
        float sunX = (float) -Math.sin(angle);
        float sunY = (float) Math.cos(angle);

        float day = smoothstep(-0.05f, 0.25f, sunY);
        float night = smoothstep(-0.05f, 0.25f, -sunY);
        float weather = 1.0f - 0.75f * world.getRainStrength(partialTicks)
            - 0.15f * world.getWeightedThunderStrength(partialTicks);

        if (day >= night) {
            lightDirection.set(sunX, sunY, 0, day * weather);
            float warm = 1.0f - smoothstep(0.0f, 0.35f, sunY);
            lightColour.set(
                lerp(SUN_COLOUR.x, SUNSET_COLOUR.x, warm),
                lerp(SUN_COLOUR.y, SUNSET_COLOUR.y, warm),
                lerp(SUN_COLOUR.z, SUNSET_COLOUR.z, warm),
                1);
        } else {
            lightDirection.set(-sunX, -sunY, 0, 0.45f * night * weather);
            lightColour.set(MOON_COLOUR.x, MOON_COLOUR.y, MOON_COLOUR.z, 1);
        }
    }

    private void updateFog(Minecraft mc) {
        if (!Nvidium.config.render_fog) {
            fogColour.set(0, 0, 0, 0);
            fogStart = fogEnd = 0;
            return;
        }

        Vector3d colour = GLStateManager.getFogColor();
        fogColour.set((float) colour.x, (float) colour.y, (float) colour.z, 255);

        float renderDistance = mc.gameSettings.renderDistanceChunks * 16f;
        FogStateStack fog = GLStateManager.getFogState();
        if (fog.getEnd() < renderDistance * 0.9f) {
            // Underwater, lava, blindness and similar: keep Minecraft's dense fog
            fogStart = fog.getStart();
            fogEnd = fog.getEnd();
            return;
        }

        float far = renderDistance;
        if (DISTANT_HORIZONS) {
            far = Math.max(far, DistantHorizonsInfo.renderDistanceBlocks());
        }
        fogEnd = far;
        // With Distant Horizons the terrain edge is not the end of the world, so only add light atmospheric haze
        fogStart = far > renderDistance ? far * 0.15f : far * 0.5f;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
