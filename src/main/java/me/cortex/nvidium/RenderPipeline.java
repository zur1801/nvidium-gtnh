package me.cortex.nvidium;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glMemoryBarrier;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.nglClearNamedBufferData;
import static org.lwjgl.opengl.GL45.nglClearNamedBufferSubData;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;

import java.util.BitSet;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.Util;

import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.SsboBuffer;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.mixin.celeritas.CompactChunkVertexAccessor;
import me.cortex.nvidium.renderers.PrimaryTerrainRasterizer;
import me.cortex.nvidium.renderers.RegionRasterizer;
import me.cortex.nvidium.renderers.SectionRasterizer;
import me.cortex.nvidium.renderers.SortRegionSectionPhase;
import me.cortex.nvidium.renderers.TemporalTerrainRasterizer;
import me.cortex.nvidium.renderers.TranslucentTerrainRasterizer;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.FrameTimeProfiler;
import me.cortex.nvidium.util.TickableManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public class RenderPipeline {

    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private PrimaryTerrainRasterizer terrainRasterizer;
    private RegionRasterizer regionRasterizer;
    private SectionRasterizer sectionRasterizer;
    private TemporalTerrainRasterizer temporalRasterizer;
    private TranslucentTerrainRasterizer translucencyTerrainRasterizer;
    private SortRegionSectionPhase regionSectionSorter;

    private final SsboBuffer sceneUniform;
    private final SceneEnvironment environment = new SceneEnvironment();
    private final SsboBuffer regionIndicies;
    private static final int SCENE_SIZE = (int) alignUp(4 * 4 * 4 + // mat4 MVP
        4 * 4 * 4 + // mat4 MVPInv (Optional)
        4 * 4 + // ivec4 chunkPosition
        4 * 4 + // vec4 subchunkOffset
        4 * 4 + // vec4 fogColour
        4 * 4 + // vec4 lightDirection
        4 * 4 + // vec4 lightColour
        4 * 2 + // vec2 screenSize
        4 * 2 + // vec2 texCoordShrink
        4 + // float fogStart
        4 + // float fogEnd
        4 + // bool isCylindricalFog
        4 + // uint flags
        4 + // uint16_t regionCount
        4 // uint8_t frameId
        , 2);

    private final SsboBuffer regionVisibility;
    private final SsboBuffer sectionVisibility;
    private final SsboBuffer terrainCommandBuffer;
    private final SsboBuffer translucencyCommandBuffer;
    private final SsboBuffer regionSortingList;
    private final SsboBuffer statisticsBuffer;
    private final SsboBuffer transformationArray;
    private final SsboBuffer originOffsetArray;

    private final BitSet regionVisibilityTracker;

    // Set of regions that need to be sorted
    private final IntSet regionsToSort = new IntOpenHashSet();

    private static final class Statistics {

        public int frustumCount;
        public int regionCount;
        public int sectionCount;
        public int quadCount;
        public int cullCount;
    }

    private final Statistics stats;
    private final FrameTimeProfiler primaryFrameTimeProfiler = new FrameTimeProfiler(100);
    private final FrameTimeProfiler transluscentFrameTimeProfiler = new FrameTimeProfiler(100);

    public RenderPipeline(RenderDevice device, UploadingBufferStream uploadStream, DownloadTaskStream downloadStream,
        SectionManager sectionManager) {
        this.device = device;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.sectionManager = sectionManager;
        this.compiledForFog = Nvidium.config.render_fog;

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();

        int maxRegions = sectionManager.getRegionManager()
            .maxRegions();

        sceneUniform = new SsboBuffer(SCENE_SIZE);
        regionIndicies = new SsboBuffer(maxRegions * 4L);
        regionVisibility = new SsboBuffer(maxRegions * 4L);
        sectionVisibility = new SsboBuffer(maxRegions * 1024L);
        terrainCommandBuffer = new SsboBuffer(maxRegions * 16L);
        translucencyCommandBuffer = new SsboBuffer(maxRegions * 16L);
        regionSortingList = new SsboBuffer(maxRegions * 4L);
        this.transformationArray = new SsboBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * (4 * 4 * 4));
        this.originOffsetArray = new SsboBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * 8);

        regionVisibilityTracker = new BitSet(maxRegions);
        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        statisticsBuffer = new SsboBuffer(4 * 4);
        stats = new Statistics();

        // Initialize the transformationArray buffer to the identity affine transform
        {
            long ptr = this.uploadStream
                .upload(this.transformationArray, 0, RegionManager.MAX_TRANSFORMATION_COUNT * (4 * 4 * 4));
            var transform = new Matrix4f().identity();
            for (int i = 0; i < RegionManager.MAX_TRANSFORMATION_COUNT; i++) {
                transform.getToAddress(ptr);
                ptr += 4 * 4 * 4;
            }
        }
        // Clear the origin offset
        nglClearNamedBufferData(this.originOffsetArray.getId(), GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);

    }

    public void setTransformation(int id, Matrix4fc transform) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.transformationArray, id * (4 * 4 * 4), 4 * 4 * 4);
        transform.getToAddress(ptr);
    }

    public void setOrigin(int id, int x, int y, int z) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.originOffsetArray, id * 8, 8);
        long pos = 0;
        pos |= x & 0x1ffffff;
        pos |= ((long) (z & 0x1ffffff)) << 25;
        pos |= ((long) (y & 0x3fff)) << 50;

        MemoryUtil.memPutLong(ptr, pos);
    }

    private int prevRegionCount;

    /**
     * Depth partitioning: the 24-bit depth buffer cannot tell surfaces a few blocks apart at long range (with the
     * 0.05 near plane, about 2.5 blocks at 1500 blocks), so distant leaves, grass and snow z-fight. Terrain is drawn
     * twice: first everything with a projection whose near plane is this distance (the GPU clips what is closer),
     * then the depth buffer is cleared and the regions within this distance are drawn with Minecraft's projection and
     * a clip plane at this distance. Every pixel is split exactly at this view depth, each half gets the full depth
     * precision, and nearby depth stays identical to what entities, particles and clouds use.
     */
    public static final double DEPTH_SPLIT_DISTANCE = 160;
    // Regions reaching into this distance; regions are sorted near to far, so they form a prefix of the list
    private int prevNearRegionCount;
    private boolean prevDepthSplit;
    private final Matrix4f nearMvp = new Matrix4f();
    private final Matrix4f farMvp = new Matrix4f();
    private final java.nio.ByteBuffer matrixScratch = MemoryUtil.memAlloc(4 * 4 * 4 * 2);
    private int frameId;
    private boolean compiledForFog = false;

    // TODO FIXME: regions that where in frustum but are now out of frustum must have the visibility data cleared
    // this is due to funny issue of pain where the section was "visible" last frame cause it didnt get ticked
    public void renderFrame(Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz) {// NOTE: can
                                                                                                         // use any of
                                                                                                         // the command
                                                                                                         // list
                                                                                                         // rendering
                                                                                                         // commands to
                                                                                                         // basicly draw
                                                                                                         // X indirects
                                                                                                         // using the
                                                                                                         // same shader,
                                                                                                         // thus
                                                                                                         // allowing for
                                                                                                         // terrain to
                                                                                                         // be rendered
                                                                                                         // very
                                                                                                         // efficently

        if (sectionManager.getRegionManager()
            .regionCount() == 0) return;// Dont render anything if there is nothing to render

        final int DEBUG_RENDER_LEVEL = 0;// 0: no debug, 1: region debug, 2: section debug
        final boolean WRITE_DEPTH = false;

        /*
         * for (int i = 0; i <3*3*3;i++) {
         * new NvidiumAPI("nvidium").setRegionTransformId(1, i%3, (i/3)%3, ((i/3)/3)%3);
         * }
         * new NvidiumAPI("nvidium").setTransformation(1, new Matrix4f().identity().scale(1,1 ,1));
         * new NvidiumAPI("nvidium").setOrigin(1, 0,0,0);
         */

        Vector3i blockPos = new Vector3i(((int) Math.floor(px)), ((int) Math.floor(py)), ((int) Math.floor(pz)));
        Vector3i chunkPos = new Vector3i(blockPos.x >> 4, blockPos.y >> 4, blockPos.z >> 4);
        // /tp @p 0.0 -1.62 0.0 0 0
        // Clear the first gl error, not our fault
        GL30C.glGetError();
        int err;

        int screenWidth = Minecraft.getMinecraft().displayWidth;
        int screenHeight = Minecraft.getMinecraft().displayHeight;

        var textureAtlas = ((TextureMap) Minecraft.getMinecraft()
            .getTextureManager()
            .getTexture(TextureMap.locationBlocksTexture)).getAtlasSprite("terrain.png");

        double subTexelPrecision = (1 << (Util.getOSType() == Util.EnumOS.OSX ? 4 : 8));
        double subTexelOffset = 1.0f / CompactChunkVertexAccessor.nvidium$getTextureMaxValue();

        float subTexelWidth = (float) (subTexelOffset - (((1.0D / textureAtlas.getIconWidth()) / subTexelPrecision)));
        float subTexelHeight = (float) (subTexelOffset - (((1.0D / textureAtlas.getIconHeight()) / subTexelPrecision)));

        int visibleRegions = 0;
        int nearRegions = 0;
        boolean depthSplit = false;

        long queryAddr = 0;
        var rm = sectionManager.getRegionManager();

        short[] regionMap;
        // Enqueue all the visible regions
        {

            // The region data indicies is located at the end of the sceneUniform
            IntSortedSet regions = new IntAVLTreeSet();
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (!rm.regionExists(i)) continue;
                if ((Nvidium.config.region_keep_distance != 257 && Nvidium.config.region_keep_distance != 32
                    && Nvidium.config.region_keep_distance > Nvidium.Compat.getEffectiveRenderDistance())
                    && !rm
                        .withinSquare(Nvidium.config.region_keep_distance + 4, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    removeRegion(i);
                    continue;
                }

                if (rm.isRegionVisible(frustum, i)) {
                    // Note, its sorted like this because of overdraw, also the translucency command buffer is written
                    // to
                    // in a reverse order to this in the section_raster/task.glsl shader
                    double distance = rm.nearestDistance(i, px, py, pz);
                    regions.add(((int) Math.min(distance, 32767) << 16) | i);
                    if (distance < DEPTH_SPLIT_DISTANCE) {
                        nearRegions++;
                    }
                    // A region is at most ~192 blocks across, so anything this close may still lie entirely in front
                    // of the split; only bother splitting once something can reach past it
                    if (distance + 192 > DEPTH_SPLIT_DISTANCE) {
                        depthSplit = true;
                    }
                    visibleRegions++;
                    regionVisibilityTracker.set(i);

                    if (rm.isRegionInACameraAxis(i, px, py, pz)) {
                        regionsToSort.add(i);
                    }

                } else {
                    if (regionVisibilityTracker.get(i)) {// Going from visible to non visible
                        // Clear the visibility bits
                        if (Nvidium.config.enable_temporal_coherence) {
                            nglClearNamedBufferSubData(
                                sectionVisibility.getId(),
                                GL_R8UI,
                                (long) i << 10,
                                1024,
                                GL_RED_INTEGER,
                                GL_UNSIGNED_INT,
                                0);
                        }
                    }
                    regionVisibilityTracker.clear(i);
                }

            }

            regionMap = new short[regions.size()];
            if (visibleRegions == 0) return;
            long addr = uploadStream.upload(regionIndicies, 0, visibleRegions * 4L);
            queryAddr = addr;// This is ungodly hacky
            int j = 0;
            for (int i : regions) {
                regionMap[j] = (short) i;
                MemoryUtil.memPutInt(addr + ((long) j << 2), i & 0xFFFF);
                j++;
            }

            if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
                stats.frustumCount = regions.size();
            }
        }

        {
            Vector3f delta = new Vector3f(
                (float) (px - (chunkPos.x << 4)),
                (float) (py - (chunkPos.y << 4)),
                (float) (pz - (chunkPos.z << 4)));
            delta.negate();
            environment.update();
            long addr = uploadStream.upload(sceneUniform, 0, SCENE_SIZE);
            nearMvp.set(crm.projection())
                .mul(crm.modelView())
                .translate(delta);// Translate the subchunk position
            farMvp.set(withNearPlane(crm.projection(), (float) DEPTH_SPLIT_DISTANCE))
                .mul(crm.modelView())
                .translate(delta);
            nearMvp.getToAddress(addr);
            addr += 4 * 4 * 4;
            if (this.compiledForFog) {
                new Matrix4f(crm.projection()).mul(crm.modelView())
                    .invert()
                    .getToAddress(addr);
                addr += 4 * 4 * 4;
            }
            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0).getToAddress(addr);// Chunk the camera is in
            addr += 16;
            new Vector4f(new Vector3f(delta), 0).getToAddress(addr);// Subchunk offset (note, delta is already negated)
            addr += 16;
            environment.fogColour.getToAddress(addr); // Fog colour, alpha 255 = fog on
            addr += 16;
            environment.lightDirection.getToAddress(addr);
            addr += 16;
            environment.lightColour.getToAddress(addr);
            addr += 16;
            // Convert it into the expected size values and floats
            MemoryUtil.memPutFloat(addr, ((float) screenWidth) / 2);
            addr += 4;
            MemoryUtil.memPutFloat(addr, ((float) screenHeight) / 2);
            addr += 4;
            MemoryUtil.memPutFloat(addr, subTexelWidth);
            addr += 4;
            MemoryUtil.memPutFloat(addr, subTexelHeight);
            addr += 4;
            MemoryUtil.memPutFloat(addr, environment.fogStart);
            addr += 4;
            MemoryUtil.memPutFloat(addr, environment.fogEnd);
            addr += 4;
            MemoryUtil.memPutInt(addr, 1);// isCylindricalFog, like Celeritas' terrain fog
            addr += 4;
            int flags = 0;
            flags |= Nvidium.Compat.getUseBlockFaceCulling() ? 1 : 0;
            MemoryUtil.memPutInt(addr, flags);// Flags
            addr += 4;
            MemoryUtil.memPutInt(addr, visibleRegions);
            addr += 4;
            MemoryUtil.memPutInt(addr, frameId++);
        }

        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.NONE) {
            regionsToSort.clear();
        }

        int regionSortSize = this.regionsToSort.size();

        if (regionSortSize != 0) {
            long regionSortUpload = uploadStream.upload(regionSortingList, 0, regionSortSize * 4L);
            for (int region : regionsToSort) {
                MemoryUtil.memPutInt(regionSortUpload, region);
                regionSortUpload += 4;
            }
            regionsToSort.clear();
        }

        sectionManager.commitChanges();// Commit all uploads done to the terrain and meta data
        uploadStream.commit();

        TickableManager.TickAll();

        if ((err = GL30C.glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: " + err);
        }

        // Bind the uniform, it doesnt get wiped between shader changes
        bindBuffers();

        glEnable(GL_DEPTH_CLAMP);
        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            if (prevDepthSplit) {
                // Far half: everything, with the split as near plane. Depth clamping is turned off so the GPU really
                // clips what is closer. Water/glass is drawn back to front right after, before its depth is lost.
                glDisable(GL_DEPTH_CLAMP);
                writeSceneMatrices(farMvp);
                terrainRasterizer.raster(0, prevRegionCount, terrainCommandBuffer.getId(), primaryFrameTimeProfiler);
                drawTranslucent(0, prevRegionCount, null);
                writeSceneMatrices(nearMvp);
                glEnable(GL_DEPTH_CLAMP);

                // Near half: Minecraft's projection, clipped at the split
                glDepthMask(true);
                GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
                if (prevNearRegionCount > 0) {
                    glEnable(GL30C.GL_CLIP_DISTANCE0);
                    terrainRasterizer.raster(0, prevNearRegionCount, terrainCommandBuffer.getId(), null);
                    glDisable(GL30C.GL_CLIP_DISTANCE0);
                }
            } else {
                terrainRasterizer.raster(0, prevRegionCount, terrainCommandBuffer.getId(), primaryFrameTimeProfiler);
            }
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        // NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        if (DEBUG_RENDER_LEVEL == 1 && WRITE_DEPTH) {
            glDepthMask(true);
        }
        if (DEBUG_RENDER_LEVEL != 1) {
            glColorMask(false, false, false, false);
        }
        if (DEBUG_RENDER_LEVEL == 0 && GL.getCapabilities().GL_NV_representative_fragment_test) {
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }

        regionRasterizer.raster(visibleRegions);

        if (DEBUG_RENDER_LEVEL == 1) {
            glColorMask(false, false, false, false);
        }

        // glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        // glColorMask(true, true, true, true);

        if (DEBUG_RENDER_LEVEL == 2) {
            glColorMask(true, true, true, true);
        }
        if (DEBUG_RENDER_LEVEL == 2 && WRITE_DEPTH) {
            glDepthMask(true);
        }

        sectionRasterizer.raster(visibleRegions);
        if (GL.getCapabilities().GL_NV_representative_fragment_test) {
            glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }
        glDepthMask(true);
        glColorMask(true, true, true, true);

        // glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        // Do temporal rasterization
        if (Nvidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            if (depthSplit) {
                // The depth buffer now holds the near half only, so newly visible sections are drawn clipped to it;
                // their far parts show up next frame in the far half
                if (nearRegions > 0) {
                    glEnable(GL30C.GL_CLIP_DISTANCE0);
                    temporalRasterizer.raster(0, nearRegions, terrainCommandBuffer.getId());
                    glDisable(GL30C.GL_CLIP_DISTANCE0);
                }
            } else {
                temporalRasterizer.raster(0, visibleRegions, terrainCommandBuffer.getId());
            }
        }
        prevRegionCount = visibleRegions;
        prevNearRegionCount = nearRegions;
        prevDepthSplit = depthSplit;

        {// Do proper visibility tracking
            glDepthMask(false);
            glColorMask(false, false, false, false);
            if (GL.getCapabilities().GL_NV_representative_fragment_test) {
                glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
            regionVisibilityTracking.computeVisibility(visibleRegions, regionVisibility, regionMap);
            if (GL.getCapabilities().GL_NV_representative_fragment_test) {
                glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
            glDepthMask(true);
            glColorMask(true, true, true, true);
        }

        if (regionSortSize != 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            regionSectionSorter.dispatch(regionSortSize);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        glDepthFunc(GL11C.GL_LEQUAL);
        // glDisable(GL_DEPTH_TEST);

        if ((err = GL30C.glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: " + err);
        }
        glDisable(GL_DEPTH_CLAMP);

    }

    void enqueueRegionSort(int regionId) {
        this.regionsToSort.add(regionId);
    }

    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
    }

    /**
     * Frees GPU memory by dropping the region farthest from the camera (horizontally), so that running out of VRAM
     * shrinks the rendered area from its edge instead of punching holes near the player.
     *
     * @return false if there was no region left to remove
     */
    public boolean removeFarthestRegion(int camChunkX, int camChunkZ) {
        var rm = sectionManager.getRegionManager();
        int farthest = -1;
        int farthestDistance = -1;
        for (int i = 0; i < rm.maxRegionIndex(); i++) {
            if (!rm.regionExists(i)) continue;
            int distance = rm.horizontalDistance(i, camChunkX, camChunkZ);
            if (distance > farthestDistance) {
                farthestDistance = distance;
                farthest = i;
            }
        }
        if (farthest == -1) {
            return false;
        }
        removeRegion(farthest);
        return true;
    }

    /*
     * private void setRegionVisible(long rid) {
     * glClearNamedBufferSubData(regionVisibility.getId(), GL_R8UI, rid, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new
     * int[]{(byte)(1)});
     * }
     */
    private static final int SRC_ALPHA = 770;
    private static final int ONE_MINUS_SRC_ALPHA = 771;
    private static final int ONE = 1;

    private void drawTranslucent(int firstCommand, int count, FrameTimeProfiler profiler) {
        glEnable(GL_DEPTH_TEST);
        Nvidium.Compat.enableBlend();
        Nvidium.Compat.blendFuncSeperate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA);
        translucencyTerrainRasterizer.raster(firstCommand, count, translucencyCommandBuffer.getId(), profiler);
        Nvidium.Compat.disableBlend();
        Nvidium.Compat.blendFuncSeperate(770, 771, 1, 0);
    }

    /** Overwrites the scene's view-projection matrix (and its inverse, used for fog) between draws. */
    private void writeSceneMatrices(Matrix4f mvp) {
        mvp.get(0, matrixScratch);
        int bytes = 4 * 4 * 4;
        if (this.compiledForFog) {
            new Matrix4f(mvp).invert()
                .get(bytes, matrixScratch);
            bytes *= 2;
        }
        matrixScratch.limit(bytes)
            .position(0);
        GL45C.glNamedBufferSubData(sceneUniform.getId(), 0, matrixScratch);
        matrixScratch.clear();
    }

    /** The same perspective projection with a different near plane (keeps the far plane). */
    private static Matrix4f withNearPlane(Matrix4fc projection, float near) {
        Matrix4f result = new Matrix4f(projection);
        float far = projection.perspectiveFar();
        result.m22(-(far + near) / (far - near));
        result.m32(-2 * far * near / (far - near));
        return result;
    }

    // Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void renderTranslucent() {
        if (prevRegionCount == 0) return;
        // Need to rebind the uniform since it might have been wiped
        bindBuffers();
        // Translucency sorting
        {
            if (prevDepthSplit) {
                // The far half was drawn in renderFrame before the depth buffer was cleared. Translucent commands are
                // far-to-near, so the regions reaching into the near half are the last ones.
                if (prevNearRegionCount > 0) {
                    glEnable(GL30C.GL_CLIP_DISTANCE0);
                    drawTranslucent(
                        prevRegionCount - prevNearRegionCount,
                        prevNearRegionCount,
                        transluscentFrameTimeProfiler);
                    glDisable(GL30C.GL_CLIP_DISTANCE0);
                }
            } else {
                drawTranslucent(0, prevRegionCount, transluscentFrameTimeProfiler);
            }
        }

        // Download statistics
        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            downloadStream.download(statisticsBuffer, 0, 4 * 4, (addr) -> {
                stats.regionCount = MemoryUtil.memGetInt(addr);
                stats.sectionCount = MemoryUtil.memGetInt(addr + 4);
                stats.quadCount = MemoryUtil.memGetInt(addr + 8);
                stats.cullCount = MemoryUtil.memGetInt(addr + 12);
            });
        }

        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            // glMemoryBarrier(GL_ALL_BARRIER_BITS);
            // Stupid bloody nvidia not following spec forcing me to use a upload stream
            long upload = this.uploadStream.upload(statisticsBuffer, 0, 4 * 4);
            MemoryUtil.memSet(upload, 0, 4 * 4);
            // glClearNamedBufferSubData(statisticsBuffer.getId(), GL_R32UI, 0, 4 * 4, GL_RED_INTEGER, GL_UNSIGNED_INT,
            // new int[]{0});
        }
    }

    public void delete() {
        regionVisibilityTracking.delete();
        MemoryUtil.memFree(matrixScratch);

        sceneUniform.delete();
        regionIndicies.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        terrainCommandBuffer.delete();
        translucencyCommandBuffer.delete();
        regionSortingList.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
        this.transformationArray.delete();
        this.originOffsetArray.delete();

        if (statisticsBuffer != null) {
            statisticsBuffer.delete();
        }
    }

    public void addDebugInfo(List<String> info) {
        if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            StringBuilder builder = new StringBuilder();
            builder.append("Statistics: ");
            if (Nvidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.FRUSTUM.ordinal()) {
                builder.append("F: ")
                    .append(stats.frustumCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.REGIONS.ordinal()) {
                builder.append(", R: ")
                    .append(stats.regionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.SECTIONS.ordinal()) {
                builder.append(", S: ")
                    .append(stats.sectionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.QUADS.ordinal()) {
                builder.append(", Q: ")
                    .append(stats.quadCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.CULL.ordinal()) {
                builder.append(", C: ")
                    .append(stats.cullCount);
            }
            info.addAll(
                List.of(
                    builder.toString()
                        .split("\n")));
        }
        info.add("Primary frame time: " + String.format("%.03f", primaryFrameTimeProfiler.getAverageMs()) + "ms");
        info.add(
            "Translucent frame time: " + String.format("%.03f", transluscentFrameTimeProfiler.getAverageMs()) + "ms");
    }

    public void reloadShaders() {
        this.compiledForFog = Nvidium.config.render_fog;
        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
    }

    public void bindBuffers() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, sceneUniform.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, regionIndicies.getId());
        glBindBufferBase(
            GL_SHADER_STORAGE_BUFFER,
            2,
            sectionManager.getRegionManager()
                .getRegionBufferId());
        glBindBufferBase(
            GL_SHADER_STORAGE_BUFFER,
            3,
            sectionManager.getRegionManager()
                .getSectionBufferId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, regionVisibility.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, sectionVisibility.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, terrainCommandBuffer.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, translucencyCommandBuffer.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, regionSortingList.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, sectionManager.terrainAreana.buffer.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, sectionManager.translucencyIndexArena.buffer.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 11, transformationArray.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 12, originOffsetArray.getId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 13, statisticsBuffer.getId());
    }

}
