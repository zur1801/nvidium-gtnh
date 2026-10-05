package me.cortex.nvidium;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;

import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkSortOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkTaskOutput;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL40;

import me.cortex.nvidium.cache.CacheLocation;
import me.cortex.nvidium.cache.MeshCacheController;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.sodiumCompat.IRepackagedResult;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import me.cortex.nvidium.sodiumCompat.RepackagedSectionOutput;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public class NvidiumWorldRenderer {

    private static final RenderDevice device = new RenderDevice();

    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;
    private final RenderPipeline renderPipeline;
    @Nullable
    private final MeshCacheController meshCache;

    // Max memory that the gpu can use to store geometry in mb
    private long max_geometry_memory;
    private long last_sample_time;

    public NvidiumWorldRenderer(World world) {
        int frames = Nvidium.Compat.getCpuRenderAheadLimit() + 1;
        // 32 mb upload buffer
        this.uploadStream = new UploadingBufferStream(device, 32000000);
        // 8 mb download buffer
        this.downloadStream = new DownloadTaskStream(device, frames, 8000000);

        update_allowed_memory();
        // this.sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, 150, 24,
        // CompactChunkVertex.STRIDE);
        int vertexStride = Nvidium.config.use_sodium_vertex_format ? ChunkMeshFormats.COMPACT.getVertexFormat()
            .getStride() : NvidiumCompactChunkVertex.STRIDE;
        this.sectionManager = new SectionManager(
            device,
            max_geometry_memory * 1024 * 1024,
            uploadStream,
            vertexStride,
            this);
        this.renderPipeline = new RenderPipeline(device, uploadStream, downloadStream, sectionManager);

        MeshCacheController cache = null;
        if (Nvidium.config.mesh_cache) {
            try {
                cache = new MeshCacheController(CacheLocation.resolve(world, vertexStride), sectionManager);
            } catch (RuntimeException e) {
                Nvidium.LOGGER.error("Could not open the mesh cache, continuing without it", e);
            }
        }
        this.meshCache = cache;

    }

    public void enqueueRegionSort(int regionId) {
        this.renderPipeline.enqueueRegionSort(regionId);
    }

    public void delete() {
        if (meshCache != null) {
            meshCache.close();
        }
        uploadStream.delete();
        downloadStream.delete();
        renderPipeline.delete();

        sectionManager.destroy();
    }

    public void reloadShaders() {
        renderPipeline.reloadShaders();
    }

    public void renderFrame(Viewport viewport, ChunkRenderMatrices matrices, double x, double y, double z) {
        if (meshCache != null) {
            meshCache.tick(
                MathHelper.floor_double(x) >> 4,
                MathHelper.floor_double(z) >> 4,
                Nvidium.Compat.getEffectiveRenderDistance());
        }
        renderPipeline.renderFrame(viewport, matrices, x, y, z);

        while (sectionManager.terrainAreana.getUsedMB() > (max_geometry_memory - 100)) {
            renderPipeline.removeARegion();
        }

        if (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER
            && (System.currentTimeMillis() - last_sample_time) > 60000) {
            last_sample_time = System.currentTimeMillis();
            update_allowed_memory();
        }
    }

    public void renderTranslucent() {
        this.renderPipeline.renderTranslucent();
    }

    /**
     * Hands GL state back the way vanilla code expects it. Angelica 2.2 emulates the fixed function pipeline (used for
     * entities, items and particles) only while no program is bound, so leaving Nvidium's mesh program bound makes
     * every entity invisible. These calls are redirected into GLStateManager, keeping its cache in sync.
     */
    public void restoreGlState() {
        GL20.glUseProgram(0);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void deleteSection(RenderSection section) {
        this.sectionManager.deleteSection(section);
        if (meshCache != null) {
            meshCache.onSectionRemoved(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        }
    }

    /** A section Celeritas found to be empty without building it. */
    public void onSectionEmpty(int x, int y, int z) {
        if (meshCache != null) {
            meshCache.onSectionEmpty(x, y, z);
        }
    }

    public void uploadBuildResult(ChunkTaskOutput buildOutput) {
        if (buildOutput instanceof ChunkBuildOutput chunkBuildOutput) {
            RenderSection section = chunkBuildOutput.render;
            RepackagedSectionOutput output = ((IRepackagedResult) chunkBuildOutput).nvidium$getOutput();
            boolean empty = output == null || output.quads() == 0;
            if (empty && meshCache != null && !isChunkLoaded(section.getChunkX(), section.getChunkZ())) {
                // Celeritas reports sections of unloaded columns as empty (it skips building them). That says
                // nothing about their contents, so keep whatever the cache restored there.
                meshCache.onEmptyResultForUnloadedChunk();
                return;
            }
            this.sectionManager.uploadChunkBuildResult(chunkBuildOutput);
            if (meshCache != null) {
                meshCache.onSectionBuilt(
                    section.getChunkX(),
                    section.getChunkY(),
                    section.getChunkZ(),
                    output,
                    SectionManager.translucentQuadCounts(chunkBuildOutput));
            }
        }
        if (buildOutput instanceof ChunkSortOutput chunkSortOutput) {
            this.sectionManager.uploadChunkSort(chunkSortOutput);
        }
    }

    private static boolean isChunkLoaded(int chunkX, int chunkZ) {
        World world = Minecraft.getMinecraft().theWorld;
        return world != null && !world.getChunkFromChunkCoords(chunkX, chunkZ)
            .isEmpty();
    }

    public void addDebugInfo(ArrayList<String> debugInfo) {
        debugInfo.add("Using nvidium renderer: " + Tags.VERSION);
        if (meshCache != null) {
            meshCache.addDebugInfo(debugInfo);
        }
        /*
         * debugInfo.add("Memory limit: " + max_geometry_memory + " mb");
         * debugInfo.add("Terrain Memory MB: " +);
         * debugInfo.add(String.format("Fragmentation: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
         * debugInfo.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" +
         * sectionManager.getRegionManager().maxRegions());
         */
        debugInfo.add(
            "Mem" + (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER ? "" : " (fallback)")
                + ": "
                + (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER
                    ? this.sectionManager.terrainAreana.getAllocatedMB() + "+"
                        + this.sectionManager.translucencyIndexArena.getAllocatedMB()
                    : this.sectionManager.terrainAreana.getUsedMB() + "+"
                        + this.sectionManager.translucencyIndexArena.getUsedMB())
                + "/"
                + this.max_geometry_memory
                + String.format(", F: %.2f", sectionManager.terrainAreana.getFragmentation() * 100));
        debugInfo.add(
            "Regions: " + sectionManager.getRegionManager()
                .regionCount()
                + "/"
                + sectionManager.getRegionManager()
                    .maxRegions());
        this.renderPipeline.addDebugInfo(debugInfo);
    }

    private void update_allowed_memory() {
        if (Nvidium.config.automatic_memory && GL.getCapabilities().GL_NVX_gpu_memory_info) {
            max_geometry_memory = (glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) / 1024)
                + (sectionManager == null ? 0 : sectionManager.terrainAreana.getMemoryUsed() / (1024 * 1024));
            max_geometry_memory -= 2048;// Minus 2gb of vram
            max_geometry_memory = Math.max(2048, max_geometry_memory);// Minimum 2 gb of vram
        } else {
            max_geometry_memory = Nvidium.config.max_geometry_memory;
        }
    }

    public SectionManager getSectionManager() {
        return sectionManager;
    }

    public void setTransformation(int id, Matrix4fc transform) {
        this.renderPipeline.setTransformation(id, transform);
    }

    public void setOrigin(int id, int x, int y, int z) {
        this.renderPipeline.setOrigin(id, x, y, z);
    }

    public int getMaxGeometryMemory() {
        return (int) max_geometry_memory;
    }
}
