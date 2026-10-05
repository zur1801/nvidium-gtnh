package me.cortex.nvidium.managers;

import static me.cortex.nvidium.Nvidium.LOGGER;

import java.nio.IntBuffer;

import org.embeddedt.embeddium.impl.common.util.NativeBuffer;
import org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkSortOutput;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import org.joml.Vector3i;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryUtil;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.mojangCompat.ChunkSectionPos;
import me.cortex.nvidium.sodiumCompat.IRepackagedResult;
import me.cortex.nvidium.util.BufferArena;
import me.cortex.nvidium.util.SegmentedManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public class SectionManager {

    public static final int SECTION_SIZE = 32 + 16;

    // Sections should be grouped and batched into sizes of the count of sections in a region
    private final RegionManager regionManager;

    private final Long2IntOpenHashMap section2id = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap section2terrain = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap section2index = new Long2IntOpenHashMap();

    public final UploadingBufferStream uploadStream;
    public final BufferArena terrainAreana;
    public final BufferArena translucencyIndexArena;

    private final Long2ObjectOpenHashMap<int[]> translucencyQuadCounts = new Long2ObjectOpenHashMap<int[]>();

    private final RenderDevice device;

    private final LongSet hiddenSectionKeys = new LongOpenHashSet();

    public SectionManager(RenderDevice device, long fallbackMemorySize, UploadingBufferStream uploadStream,
        int quadVertexSize, NvidiumWorldRenderer worldRenderer) {
        int maxRegions = 50_000;

        this.device = device;
        this.uploadStream = uploadStream;

        this.terrainAreana = new BufferArena(device, fallbackMemorySize, quadVertexSize);
        // TODO adapt fallbackMemorySize
        this.translucencyIndexArena = new BufferArena(device, fallbackMemorySize, 1);
        this.regionManager = new RegionManager(
            device,
            maxRegions,
            maxRegions * 200,
            uploadStream,
            worldRenderer::enqueueRegionSort);

        this.section2id.defaultReturnValue(-1);
        this.section2terrain.defaultReturnValue(-1);
        this.section2index.defaultReturnValue(-1);

        this.translucencyQuadCounts.defaultReturnValue(null);
    }

    public void uploadIndexBuffer(IntBuffer indexBuffer, int[] quadCountData, long upload) {
        int quadOffset = 0;
        for (var facing : ModelQuadFacing.values()) {
            for (int i = 0; i < quadCountData[facing.ordinal()]; i++) {
                // We only need 1 index out of 6 because we are working with quad indexes, also /4 because we have 4
                // vertices per quad
                int idx = (indexBuffer.get((quadOffset + i) * 6) / 4) + quadOffset;
                MemoryUtil.memPutInt(upload + (long) (quadOffset + i) * 4, idx);
            }
            quadOffset += quadCountData[facing.ordinal()];
        }
    }

    public void uploadChunkSort(ChunkSortOutput sortOutput) {
        for (ChunkSortOutput.SortedMesh mesh : sortOutput.meshes.values()) {
            NativeBuffer indexBuffer = mesh.indexData();
            // Early exit
            if (indexBuffer == null) {
                return;
            }

            RenderSection section = sortOutput.render;
            long sectionKey = ChunkSectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ());
            var quadCountData = this.translucencyQuadCounts.get(sectionKey);
            if (quadCountData == null) { // early exist if we don't have a section
                return;
            }

            // Quick dirty integrity check to prevent race condition crash because translucencyQuadCounts can be
            // overridden
            // by an already reprocessed chunk
            var totalQuads = 0;
            for (var facing : ModelQuadFacing.values()) {
                totalQuads += quadCountData[facing.ordinal()];
            }
            if (totalQuads * 6 * 4 != indexBuffer.getLength()) {
                LOGGER.error(
                    "ChunkSortOutput integrity check failed, aborting (totalQuads={};indexBuffer={})",
                    totalQuads,
                    indexBuffer.getLength() / 24);
                return;
            }

            int indexDataAddress;
            {
                var idxBufferLength = indexBuffer.getLength() / 6;
                IntBuffer idxBuffer = indexBuffer.getDirectBuffer()
                    .asIntBuffer();

                indexDataAddress = this.section2index.get(sectionKey);
                if (indexDataAddress != -1
                    && !this.translucencyIndexArena.canReuse(indexDataAddress, idxBufferLength)) {
                    this.section2index.remove(sectionKey);
                    this.translucencyIndexArena.free(indexDataAddress);
                    indexDataAddress = -1;
                }

                if (indexDataAddress == -1) {
                    indexDataAddress = this.translucencyIndexArena.allocQuads(idxBufferLength);
                }

                this.section2index.put(sectionKey, indexDataAddress);

                long upload = translucencyIndexArena.upload(uploadStream, indexDataAddress);
                uploadIndexBuffer(idxBuffer, quadCountData, upload);
            }

            int sectionIdx = this.section2id.get(sectionKey);
            if (sectionIdx == -1) {
                // We shouldn't get there, section should be created by ChunkBuildOutput
                LOGGER.error("Got translucency data but no section found for {}", sectionKey);
                return;
            }

            long metadata = regionManager.setSectionData(sectionIdx);
            metadata += 32; // Go to translucency data offset
            MemoryUtil.memPutInt(metadata, indexDataAddress);
        }
    }

    /**
     * Quads per {@link ModelQuadFacing} of the translucent mesh, needed to pad translucency sorting data. Null when the
     * section has no translucent geometry.
     */
    public static int[] translucentQuadCounts(ChunkBuildOutput result) {
        BuiltSectionMeshParts translucentData = result.meshes.get(Nvidium.Compat.getTranslucentPass());
        if (translucentData == null) {
            return null;
        }
        int[] quadCounts = new int[ModelQuadFacing.COUNT];
        for (var facing : translucentData.ranges()
            .keySet()) {
            quadCounts[facing.ordinal()] = translucentData.ranges()
                .get(facing)
                .vertexCount() / 4;
        }
        return quadCounts;
    }

    public void uploadChunkBuildResult(ChunkBuildOutput result) {
        var output = ((IRepackagedResult) result).nvidium$getOutput();
        RenderSection section = result.render;
        long sectionKey = ChunkSectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ());

        if (output == null || output.quads() == 0) {
            deleteSection(sectionKey);
            return;
        }

        uploadSection(
            sectionKey,
            output.quads(),
            MemoryUtil.memAddress(
                output.geometry()
                    .getDirectBuffer()),
            output.geometry()
                .getLength(),
            output.offsets(),
            output.min(),
            output.size(),
            translucentQuadCounts(result));
    }

    /**
     * Uploads one section's geometry, either freshly built or restored from the mesh cache.
     *
     * @return false if the terrain arena is out of memory and the section was dropped
     */
    public boolean uploadSection(long sectionKey, int quads, long geometryAddress, int geometryBytes, short[] offsets,
        Vector3i min, Vector3i size, int[] translucentQuadCounts) {
        int sectionX = (int) (sectionKey << 0 >> 42);
        int sectionY = (int) (sectionKey << 44 >> 44);
        int sectionZ = (int) (sectionKey << 22 >> 42);

        if (translucentQuadCounts != null) {
            translucencyQuadCounts.put(sectionKey, translucentQuadCounts);
        } else {
            translucencyQuadCounts.remove(sectionKey);
        }

        int terrainAddress;
        {
            // Attempt to reuse the same memory
            terrainAddress = this.section2terrain.get(sectionKey);
            if (terrainAddress != -1 && !this.terrainAreana.canReuse(terrainAddress, quads)) {
                this.section2terrain.remove(sectionKey);
                this.terrainAreana.free(terrainAddress);
                terrainAddress = -1;
            }
            if (terrainAddress == -1) {
                terrainAddress = this.terrainAreana.allocQuads(quads);
            }
            if (terrainAddress == SegmentedManager.SIZE_LIMIT) {
                Nvidium.LOGGER.error(
                    "Terrain arena critically out of memory, expect issues with chunks!! " + " quad_used: "
                        + this.terrainAreana.getUsedMB()
                        + " physically used: "
                        + this.terrainAreana.getMemoryUsed()
                        + " limit: "
                        + Nvidium.Compat.getWorldRenderer()
                            .getMaxGeometryMemory());
                deleteSection(sectionKey);
                return false;
            }
            this.section2terrain.put(sectionKey, terrainAddress);
            long geometryUpload = terrainAreana.upload(uploadStream, terrainAddress);
            MemoryUtil.memCopy(geometryAddress, geometryUpload, geometryBytes);
        }

        // Get the section id or allocate a new instance for it
        int sectionIdx = this.section2id.computeIfAbsent(
            sectionKey,
            key -> this.regionManager
                .allocateSection((int) (key << 0 >> 42), (int) (key << 44 >> 44), (int) (key << 22 >> 42)));

        long metadata = regionManager.setSectionData(sectionIdx);

        boolean hideSectionBitSet = this.hiddenSectionKeys.contains(sectionKey);

        // bits 18->26 taken by section id (used for translucency sorting/rendering)
        // 26->32 is free
        int px = sectionX << 8 | size.x << 4 | min.x;
        int py = (sectionY & 0x1FF) << 8 | size.y << 4
            | min.y
            | (hideSectionBitSet ? 1 << 17 : 0)
            | ((regionManager.getSectionRefId(sectionIdx)) << 18);
        int pz = sectionZ << 8 | size.z << 4 | min.z;
        int pw = terrainAddress;
        new Vector4i(px, py, pz, pw).getToAddress(metadata);
        metadata += 4 * 4;

        // Write the geometry offsets, packed into ints
        for (int i = 0; i < 4; i++) {
            int geo = Short.toUnsignedInt(offsets[i * 2]) | (Short.toUnsignedInt(offsets[i * 2 + 1]) << 16);
            MemoryUtil.memPutInt(metadata, geo);
            metadata += 4;
        }
        return true;
    }

    public boolean hasSection(long sectionKey) {
        return this.section2id.containsKey(sectionKey);
    }

    public void setHideBit(int x, int y, int z, boolean hide) {
        long sectionKey = ChunkSectionPos.asLong(x, y, z);

        if (hide) {
            // Do a fast return if it was already hidden
            if (!this.hiddenSectionKeys.add(sectionKey)) {
                return;
            }
        } else {
            // Do a fast return if the section was not hidden
            if (!this.hiddenSectionKeys.remove(sectionKey)) {
                return;
            }
        }

        int sectionId = this.section2id.get(sectionKey);
        // Only update the section if it is loaded
        if (sectionId != -1) {
            long metadata = this.regionManager.setSectionData(sectionId);
            MemoryUtil
                .memPutInt(metadata + 4, (MemoryUtil.memGetInt(metadata + 4) & ~(1 << 17)) | (hide ? 1 : 0) << 17);
        }
    }

    public void deleteSection(RenderSection section) {
        deleteSection(ChunkSectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }

    public void deleteSection(long sectionKey) {
        int sectionIdx = this.section2id.remove(sectionKey);
        if (sectionIdx != -1) {
            int terrainIndex = this.section2terrain.remove(sectionKey);
            if (terrainIndex != -1) {
                this.terrainAreana.free(terrainIndex);
            }
            int indexIdx = this.section2index.remove(sectionKey);
            if (indexIdx != -1) {
                this.translucencyIndexArena.free(indexIdx);
            }
            this.translucencyQuadCounts.remove(sectionKey);
            // Clear the segment
            this.regionManager.removeSection(sectionIdx);
        }
    }

    public void destroy() {
        this.regionManager.destroy();
        this.terrainAreana.delete();
        this.translucencyIndexArena.delete();
    }

    public void commitChanges() {
        this.regionManager.commitChanges();
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public void removeRegionById(int regionId) {
        if (!this.regionManager.regionExists(regionId)) return;
        long rk = this.regionManager.regionIdToKey(regionId);
        int X = (int) (rk << 0 >> 42) << 3;
        int Y = (int) (rk << 44 >> 44) << 2;
        int Z = (int) (rk << 22 >> 42) << 3;
        for (int x = X; x < X + 8; x++) {
            for (int y = Y; y < Y + 4; y++) {
                for (int z = Z; z < Z + 8; z++) {
                    this.deleteSection(ChunkSectionPos.asLong(x, y, z));
                }
            }
        }
    }
}
