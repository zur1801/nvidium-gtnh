package me.cortex.nvidium.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import me.cortex.nvidium.sodiumCompat.RepackagedSectionOutput;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Serialized form of one section's Nvidium geometry: the repackaged quads plus what is needed to place them.
 */
@Lwjgl3Aware
public record SectionPayload(int quads, short[] offsets, Vector3i min, Vector3i size, int[] translucentQuadCounts,
    ByteBuffer geometry) {

    private static final int OFFSET_COUNT = 8;

    public static byte[] encode(RepackagedSectionOutput output, int[] translucentQuadCounts) {
        return encode(
            output.quads(),
            MemoryUtil.memAddress(
                output.geometry()
                    .getDirectBuffer()),
            output.geometry()
                .getLength(),
            output.offsets(),
            output.min(),
            output.size(),
            translucentQuadCounts);
    }

    public static byte[] encode(int quads, long geometryAddress, int geometryBytes, short[] offsets, Vector3i min,
        Vector3i size, int[] translucentQuadCounts) {
        int translucentInts = translucentQuadCounts == null ? 0 : translucentQuadCounts.length;
        ByteBuffer out = ByteBuffer.allocate(4 + OFFSET_COUNT * 2 + 6 + 1 + translucentInts * 4 + 4 + geometryBytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(quads);
        for (int i = 0; i < OFFSET_COUNT; i++) {
            out.putShort(offsets[i]);
        }
        out.put((byte) min.x)
            .put((byte) min.y)
            .put((byte) min.z);
        out.put((byte) size.x)
            .put((byte) size.y)
            .put((byte) size.z);
        out.put((byte) translucentInts);
        for (int i = 0; i < translucentInts; i++) {
            out.putInt(translucentQuadCounts[i]);
        }
        out.putInt(geometryBytes);
        out.put(MemoryUtil.memByteBuffer(geometryAddress, geometryBytes));
        return out.array();
    }

    /**
     * Decodes a payload. The returned geometry buffer is a direct buffer the caller must free with
     * {@link MemoryUtil#memFree}.
     */
    public static SectionPayload decode(byte[] payload) {
        ByteBuffer in = ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN);
        int quads = in.getInt();
        short[] offsets = new short[OFFSET_COUNT];
        for (int i = 0; i < OFFSET_COUNT; i++) {
            offsets[i] = in.getShort();
        }
        Vector3i min = new Vector3i(in.get(), in.get(), in.get());
        Vector3i size = new Vector3i(in.get(), in.get(), in.get());
        int translucentInts = in.get();
        int[] translucentQuadCounts = null;
        if (translucentInts > 0) {
            translucentQuadCounts = new int[translucentInts];
            for (int i = 0; i < translucentInts; i++) {
                translucentQuadCounts[i] = in.getInt();
            }
        }
        int geometryBytes = in.getInt();
        ByteBuffer geometry = MemoryUtil.memAlloc(geometryBytes);
        geometry.put(payload, in.position(), geometryBytes)
            .flip();
        return new SectionPayload(quads, offsets, min, size, translucentQuadCounts, geometry);
    }
}
