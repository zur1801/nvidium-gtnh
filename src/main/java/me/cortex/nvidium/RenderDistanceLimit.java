package me.cortex.nvidium;

import net.minecraft.client.settings.GameSettings;

/**
 * Raises the video settings render distance cap (Angelica sets it to 32). Kept out of {@link Nvidium} so the
 * client-only GameSettings class is never touched on a dedicated server.
 */
public final class RenderDistanceLimit {

    private RenderDistanceLimit() {}

    public static void apply(int maxRenderDistance) {
        GameSettings.Options option = GameSettings.Options.RENDER_DISTANCE;
        if (maxRenderDistance > option.getValueMax()) {
            option.setValueMax(maxRenderDistance);
            Nvidium.LOGGER.info("Raised maximum render distance to {} chunks", maxRenderDistance);
        }
    }
}
