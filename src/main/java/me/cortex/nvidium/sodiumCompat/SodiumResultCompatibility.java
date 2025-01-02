package me.cortex.nvidium.sodiumCompat;

import it.unimi.dsi.fastutil.longs.LongArrays;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.MinecraftClient;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

public class SodiumResultCompatibility {

    private static final int FORMAT_SIZE = 16;

    public static RepackagedSectionOutput repackage(ChunkBuildOutput result) {
        int formatSize = 16;
        int geometryBytes = result.meshes.values().stream().mapToInt(a->a.getVertexData().getLength()).sum();
        var output = new NativeBuffer(geometryBytes);
        var offsets = new short[8];
        var min = new Vector3i(2000);
        var max = new Vector3i(-2000);
        packageSectionGeometry(formatSize, output, offsets, result, min, max);

        Vector3i size;
        {
            min.x = Math.max(min.x, 0);
            min.y = Math.max(min.y, 0);
            min.z = Math.max(min.z, 0);
            min.x = Math.min(min.x, 15);
            min.y = Math.min(min.y, 15);
            min.z = Math.min(min.z, 15);

            max.x = Math.min(max.x, 16);
            max.y = Math.min(max.y, 16);
            max.z = Math.min(max.z, 16);
            max.x = Math.max(max.x, 0);
            max.y = Math.max(max.y, 0);
            max.z = Math.max(max.z, 0);

            size =  new Vector3i(max.x - min.x - 1, max.y - min.y - 1, max.z - min.z - 1);

            size.x = Math.min(15, Math.max(size.x, 0));
            size.y = Math.min(15, Math.max(size.y, 0));
            size.z = Math.min(15, Math.max(size.z, 0));
        }
        //NvidiumGeometryReencoder.transpileGeometry(repackagedGeometry);
        return new RepackagedSectionOutput((geometryBytes/formatSize)/4, output, offsets, min, size);
    }

    private static void packageSectionGeometry(int formatSize, NativeBuffer output, short[] outOffsets, ChunkBuildOutput result, Vector3i min, Vector3i max) {
        int offset = 0;
        long outPtr = MemoryUtil.memAddress(output.getDirectBuffer());

        var cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        float cpx = (float) (cameraPos.x - (result.render.getChunkX() << 4));
        float cpy = (float) (cameraPos.y - (result.render.getChunkY() << 4));
        float cpz = (float) (cameraPos.z - (result.render.getChunkZ() << 4));

        float len = (float) Math.sqrt(cpx * cpx + cpy * cpy + cpz * cpz);
        cpx *= 1 / len;
        cpy *= 1 / len;
        cpz *= 1 / len;
        len = Math.min(len, 32);
        cpx *= len;
        cpy *= len;
        cpz *= len;

        offset = handleTranslucentGeometry(formatSize, result, min, max, outPtr, cpx, cpy, cpz, offset);

        outOffsets[7] = (short) offset;

        offset = handleSolidAndCutoutGeometry(formatSize, result, min, max, outPtr, outOffsets, offset);

        if (offset * 4 * formatSize != output.getLength()) {
            throw new IllegalStateException("Offset mismatch: expected " + (offset * 4 * formatSize) + " but got " + output.getLength());
        }
    }

    // Ensure you have the following logs in the geometry methods
    private static int handleTranslucentGeometry(int formatSize, ChunkBuildOutput result, Vector3i min, Vector3i max, long outPtr, float cpx, float cpy, float cpz, int offset) {
        var translucentData = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            int quadCount = 0;
            int[] vertexCounts = translucentData.getVertexCounts();
            for (int count : vertexCounts) {
                quadCount += count / 4;
            }

            long[] sortingData = new long[quadCount];
            long[] srcs = new long[7];
            int quadId = 0;

            for (int i = 0; i < 7; i++) {
                int vertexCount = vertexCounts[i];
                if (vertexCount > 0) {
                    long src = MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer());
                    srcs[i] = src;

                    for (int j = 0; j < vertexCount; j++) {
                        long base = src + (long) j * formatSize;
                        byte flags = (byte) 0b100; // Mipping, No alpha cut
                        MemoryUtil.memPutByte(base + 6L, flags);

                        float x = decodePosition(MemoryUtil.memGetShort(base));
                        float y = decodePosition(MemoryUtil.memGetShort(base + 2));
                        float z = decodePosition(MemoryUtil.memGetShort(base + 4));
                        updateSectionBounds(min, max, x, y, z);

                        if ((j & 3) == 3) {
                            float cx = x / 4;
                            float cy = y / 4;
                            float cz = z / 4;

                            float dx = cx - cpx;
                            float dy = cy - cpy;
                            float dz = cz - cpz;

                            float dist = dx * dx + dy * dy + dz * dz;

                            int sortDistance = (int) (dist * (1 << 12));

                            long packedSortingData = (((long) sortDistance) << 32) | ((((long) j >> 2) << 3) | i);
                            sortingData[quadId++] = packedSortingData;
                        }
                    }
                }
            }

            LongArrays.radixSort(sortingData);

            for (int i = 0; i < sortingData.length; i++) {
                long data = sortingData[i];
                copyQuad(srcs[(int) (data & 7)] + ((data >> 3) & ((1L << 29) - 1)) * 4 * formatSize, outPtr + ((sortingData.length - 1) - i) * 4L * formatSize, formatSize);
            }

            offset += quadCount;
        }
        return offset;
    }

    private static int handleSolidAndCutoutGeometry(int formatSize, ChunkBuildOutput result, Vector3i min, Vector3i max, long outPtr, short[] outOffsets, int offset) {
        var solid = result.meshes.get(DefaultTerrainRenderPasses.SOLID);
        var cutout = result.meshes.get(DefaultTerrainRenderPasses.CUTOUT);

        for (int i = 0; i < 7; i++) {
            int poff = offset;
            offset = handleGeometryType(formatSize, solid, outPtr, min, max, offset, i);
            offset = handleGeometryType(formatSize, cutout, outPtr, min, max, offset, i);
            outOffsets[i] = (short) (offset - poff);
        }
        return offset;
    }

    private static int handleGeometryType(int formatSize, BuiltSectionMeshParts geometryType, long outPtr, Vector3i min, Vector3i max, int offset, int i) {
        if (geometryType != null) {
            int vertexCount = geometryType.getVertexCounts()[i];
            if (vertexCount > 0) {
                long src = MemoryUtil.memAddress(geometryType.getVertexData().getDirectBuffer());
                long dst = outPtr + offset * 4L * formatSize;
                MemoryUtil.memCopy(src, dst, (long) vertexCount * formatSize);

                for (int j = 0; j < vertexCount; j++) {
                    long base = dst + (long) j * formatSize;
                    byte flags = (byte) 0b100;
                    MemoryUtil.memPutByte(base + 6L, flags);

                    updateSectionBounds(min, max, base);
                }

                offset += vertexCount / 4;
            }
        }
        return offset;
    }

    private static void copyQuad(long src, long dst, int formatSize) {
        int quadSize = 4 * formatSize; // Assuming a quad consists of 4 vertices
        MemoryUtil.memCopy(src, dst, quadSize);
    }


    private static float decodePosition(short v) {
        return Short.toUnsignedInt(v) * (1f / 2048.0f) - 8.0f;
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, long vertex) {
        float x = decodePosition(MemoryUtil.memGetShort(vertex));
        float y = decodePosition(MemoryUtil.memGetShort(vertex + 2));
        float z = decodePosition(MemoryUtil.memGetShort(vertex + 4));
        updateSectionBounds(min, max, x, y, z);
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, float x, float y, float z) {
        min.x = (int) Math.min(min.x, Math.floor(x));
        min.y = (int) Math.min(min.y, Math.floor(y));
        min.z = (int) Math.min(min.z, Math.floor(z));

        max.x = (int) Math.max(max.x, Math.ceil(x));
        max.y = (int) Math.max(max.y, Math.ceil(y));
        max.z = (int) Math.max(max.z, Math.ceil(z));
    }
}
