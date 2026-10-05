package me.cortex.nvidium.cache;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.lwjgl.system.MemoryUtil;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.mojangCompat.ChunkSectionPos;
import me.cortex.nvidium.sodiumCompat.RepackagedSectionOutput;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Connects the {@link MeshCache} to a running {@link SectionManager}: geometry built this session is written to the
 * cache, and cached geometry around the camera is shown until Celeritas has rebuilt those sections.
 */
@Lwjgl3Aware
public class MeshCacheController {

    /** Geometry restored from the cache per frame, to keep the upload stream from stalling. */
    private static final long UPLOAD_BUDGET_BYTES = 16L << 20;
    private static final int FLUSH_INTERVAL_MS = 15_000;

    private final MeshCache cache;
    private final SectionManager sectionManager;

    /** Sections built (or found empty) this session; cached copies of them are stale. */
    private final LongOpenHashSet liveSections = new LongOpenHashSet();
    /** Sections on the GPU only because of the cache. Celeritas never removes these, so they are evicted here. */
    private final LongOpenHashSet cacheOnlySections = new LongOpenHashSet();
    private final LongOpenHashSet requestedRegions = new LongOpenHashSet();

    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private int lastRenderDistance = -1;
    private long lastFlush = System.currentTimeMillis();
    private long restoredSections;

    // Diagnostics, logged periodically so cache behaviour can be checked from the game log
    private static final int STATS_INTERVAL_MS = 10_000;
    private long lastStats = System.currentTimeMillis();
    private String lastStatsLine = "";
    private long regionsRequested;
    private long skippedLive;
    private long skippedPresent;
    private long skippedFar;
    private long evicted;
    private long removedByCeleritas;
    private long builtWritten;
    private long emptyBuildsWritten;
    private long emptyAddsWritten;
    private long emptyUnloadedIgnored;

    public MeshCacheController(File dir, SectionManager sectionManager) {
        this.cache = new MeshCache(dir);
        this.sectionManager = sectionManager;
    }

    public void onSectionBuilt(int x, int y, int z, RepackagedSectionOutput output, int[] translucentQuadCounts) {
        long key = ChunkSectionPos.asLong(x, y, z);
        liveSections.add(key);
        cacheOnlySections.remove(key);
        if (output == null || output.quads() == 0) {
            emptyBuildsWritten++;
        } else {
            builtWritten++;
        }
        byte[] payload = output == null || output.quads() == 0 ? null
            : SectionPayload.encode(output, translucentQuadCounts);
        cache.put(MeshCache.regionKey(x, z), key, payload);
    }

    public void onSectionEmpty(int x, int y, int z) {
        emptyAddsWritten++;
        long key = ChunkSectionPos.asLong(x, y, z);
        liveSections.add(key);
        if (cacheOnlySections.remove(key)) {
            sectionManager.deleteSection(key);
        }
        cache.put(MeshCache.regionKey(x, z), key, null);
    }

    /** Called when the GPU copy of a section was dropped because Celeritas unloaded it. */
    public void onSectionRemoved(int x, int y, int z) {
        if (cacheOnlySections.remove(ChunkSectionPos.asLong(x, y, z))) {
            removedByCeleritas++;
        }
    }

    public void onEmptyResultForUnloadedChunk() {
        emptyUnloadedIgnored++;
    }

    /** Per-frame work on the render thread. */
    public void tick(int cameraChunkX, int cameraChunkZ, int renderDistance) {
        if (cameraChunkX != lastChunkX || cameraChunkZ != lastChunkZ || renderDistance != lastRenderDistance) {
            lastChunkX = cameraChunkX;
            lastChunkZ = cameraChunkZ;
            lastRenderDistance = renderDistance;
            requestRegionsAround(cameraChunkX, cameraChunkZ, renderDistance);
            evictFarSections(cameraChunkX, cameraChunkZ, renderDistance);
        }

        uploadLoaded(cameraChunkX, cameraChunkZ, renderDistance);

        long now = System.currentTimeMillis();
        if (now - lastStats > STATS_INTERVAL_MS) {
            lastStats = now;
            logStats();
        }
        if (now - lastFlush > FLUSH_INTERVAL_MS) {
            lastFlush = now;
            cache.flushAsync();
        }
    }

    private void requestRegionsAround(int cx, int cz, int renderDistance) {
        int radius = (renderDistance >> 3) + 1;
        int rcx = cx >> 3;
        int rcz = cz >> 3;

        // Forget regions that went out of range so they are read again when the camera returns
        LongIterator it = requestedRegions.iterator();
        while (it.hasNext()) {
            long region = it.nextLong();
            int rx = (int) (region >> 32);
            int rz = (int) region;
            if (Math.max(Math.abs(rx - rcx), Math.abs(rz - rcz)) > radius + 1) {
                it.remove();
            }
        }

        // Nearest regions first
        List<long[]> wanted = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long region = MeshCache.regionKey((rcx + dx) << 3, (rcz + dz) << 3);
                if (!requestedRegions.contains(region)) {
                    wanted.add(new long[] { region, (long) dx * dx + (long) dz * dz });
                }
            }
        }
        wanted.sort(Comparator.comparingLong(a -> a[1]));
        for (long[] region : wanted) {
            requestedRegions.add(region[0]);
            regionsRequested++;
            cache.requestRegion(region[0]);
        }
    }

    private void evictFarSections(int cx, int cz, int renderDistance) {
        int limit = renderDistance + 2;
        LongIterator it = cacheOnlySections.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int sx = (int) (key << 0 >> 42);
            int sz = (int) (key << 22 >> 42);
            if (Math.max(Math.abs(sx - cx), Math.abs(sz - cz)) > limit) {
                it.remove();
                sectionManager.deleteSection(key);
                evicted++;
            }
        }
    }

    private void uploadLoaded(int cx, int cz, int renderDistance) {
        long budget = UPLOAD_BUDGET_BYTES;
        MeshCache.Entry entry;
        while (budget > 0 && (entry = cache.pollLoaded()) != null) {
            long key = entry.sectionKey();
            int sx = (int) (key << 0 >> 42);
            int sz = (int) (key << 22 >> 42);
            if (liveSections.contains(key)) {
                skippedLive++;
                continue;
            }
            if (sectionManager.hasSection(key)) {
                skippedPresent++;
                continue;
            }
            if (Math.max(Math.abs(sx - cx), Math.abs(sz - cz)) > renderDistance + 1) {
                skippedFar++;
                continue;
            }
            SectionPayload payload;
            try {
                payload = SectionPayload.decode(entry.payload());
            } catch (RuntimeException e) {
                Nvidium.LOGGER.warn("Skipping corrupt mesh cache entry", e);
                continue;
            }
            try {
                if (sectionManager.uploadSection(
                    key,
                    payload.quads(),
                    MemoryUtil.memAddress(payload.geometry()),
                    payload.geometry()
                        .remaining(),
                    payload.offsets(),
                    payload.min(),
                    payload.size(),
                    payload.translucentQuadCounts())) {
                    cacheOnlySections.add(key);
                    restoredSections++;
                }
            } finally {
                MemoryUtil.memFree(payload.geometry());
            }
            budget -= entry.payload().length;
        }
    }

    public void addDebugInfo(List<String> debugInfo) {
        debugInfo.add(
            "Mesh cache: restored " + restoredSections
                + ", showing "
                + cacheOnlySections.size()
                + ", unsaved "
                + (cache.getPendingBytes() >> 20)
                + " MB");
    }

    private void logStats() {
        String line = "regions requested " + regionsRequested
            + ", files read "
            + cache.regionFilesRead.get()
            + ", sections read "
            + cache.entriesRead.get()
            + ", restored "
            + restoredSections
            + ", skipped (rebuilt "
            + skippedLive
            + ", already shown "
            + skippedPresent
            + ", too far "
            + skippedFar
            + "), showing from cache "
            + cacheOnlySections.size()
            + ", evicted "
            + evicted
            + ", unloaded by Celeritas "
            + removedByCeleritas
            + ", saved "
            + builtWritten
            + ", saved empty (built "
            + emptyBuildsWritten
            + ", added "
            + emptyAddsWritten
            + "), ignored empty in unloaded chunks "
            + emptyUnloadedIgnored;
        if (!line.equals(lastStatsLine)) {
            lastStatsLine = line;
            Nvidium.LOGGER.info("Mesh cache: {}", line);
        }
    }

    public void close() {
        logStats();
        cache.close();
    }
}
