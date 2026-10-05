package me.cortex.nvidium;

import com.seibel.distanthorizons.api.DhApi;

/**
 * Reads Distant Horizons settings. Only loaded when Distant Horizons is installed, so its API classes are never
 * touched otherwise.
 */
final class DistantHorizonsInfo {

    private DistantHorizonsInfo() {}

    /** How far Distant Horizons renders, in blocks, or 0 if it is not rendering. */
    static float renderDistanceBlocks() {
        try {
            if (DhApi.Delayed.configs == null || !DhApi.Delayed.configs.graphics()
                .renderingEnabled()
                .getValue()) {
                return 0;
            }
            return DhApi.Delayed.configs.graphics()
                .chunkRenderDistance()
                .getValue() * 16f;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }
}
