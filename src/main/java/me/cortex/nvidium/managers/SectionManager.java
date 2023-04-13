package me.cortex.nvidium.managers;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.sodiumCompat.SodiumResultCompatibility;
import me.cortex.nvidium.util.BufferArena;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import net.minecraft.util.math.ChunkSectionPos;
import org.joml.Vector3i;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryUtil;

public class SectionManager {
    public static final int SECTION_SIZE = 32;

    //Sections should be grouped and batched into sizes of the count of sections in a region
    private final RegionManager regionManager;

    //TODO: maybe replace with a int[] using bit masking thing
    private final Long2IntOpenHashMap sectionOffset = new Long2IntOpenHashMap();

    private final Long2IntOpenHashMap terrainDataLocation = new Long2IntOpenHashMap();

    public final UploadingBufferStream uploadStream;

    private final IDeviceMappedBuffer sectionBuffer;
    public final BufferArena terrainAreana;

    private final RenderDevice device;

    private final int formatSize;
    public SectionManager(RenderDevice device, int rd, int height, int frames, int quadVertexSize) {
        this.uploadStream = new UploadingBufferStream(device, frames, 160000000);
        int widthSquared = (rd*2+1)*(rd*2+1);

        this.formatSize = quadVertexSize;
        this.sectionBuffer = device.createDeviceOnlyMappedBuffer((long) widthSquared * height * SECTION_SIZE);
        this.terrainAreana = new BufferArena(device, quadVertexSize);
        this.sectionOffset.defaultReturnValue(-1);
        this.regionManager = new RegionManager(device, (int) Math.ceil(((double) widthSquared/(8*8))*((double) height/4)+1));
        this.device = device;
    }

    private long getSectionKey(int x, int y, int z) {
        return ChunkSectionPos.asLong(x,y,z);
    }

    //FIXME: causes extreame stuttering
    public void uploadSetSection(ChunkBuildResult result) {
        int geometrySize = SodiumResultCompatibility.getTotalGeometryQuadCount(result);
        if (result.meshes.isEmpty() || result.data == null || result.data == ChunkRenderData.ABSENT || result.data == ChunkRenderData.EMPTY || geometrySize == 0) {
            deleteSection(result.render);
            return;
        }
        RenderSection section = result.render;
        long key = getSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        int sectionIdx = sectionOffset.computeIfAbsent(//Get or fetch the section meta index
                key,
                a->regionManager.createSectionIndex(uploadStream, section.getChunkX(), section.getChunkY(), section.getChunkZ())
        );



        if (terrainDataLocation.containsKey(key)) {
            terrainAreana.free(terrainDataLocation.get(key));
        }

        int addr = terrainAreana.allocQuads(geometrySize);
        terrainDataLocation.put(key, addr);
        long geoUpload = terrainAreana.upload(uploadStream, addr);
        short[] offsets = new short[8];
        //Upload all the geometry grouped by face
        SodiumResultCompatibility.uploadChunkGeometry(geoUpload, offsets, result);



        long segment = uploadStream.getUpload(sectionBuffer, (long) sectionIdx * SECTION_SIZE, SECTION_SIZE);
        Vector3i min  = SodiumResultCompatibility.getMinBounds(result);
        Vector3i size = SodiumResultCompatibility.getSizeBounds(result);

        int px = section.getChunkX()<<8  | size.x<<4 | min.x;
        int py = section.getChunkY()<<24 | size.y<<4 | min.y;
        int pz = section.getChunkZ()<<8  | size.z<<4 | min.z;
        int pw = addr;
        new Vector4i(px, py, pz, pw).getToAddress(segment);
        segment += 4*4;

        //Write the geometry offsets, packed into ints
        for (int i = 0; i < 4; i++) {
            int geo = Short.toUnsignedInt(offsets[i*2])|(Short.toUnsignedInt(offsets[i*2+1])<<16);
            MemoryUtil.memPutInt(segment, geo);
            segment += 4;
        }
    }

    public void deleteSection(RenderSection section) {
        long key = getSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        int sectionIdx = sectionOffset.remove(key);
        if (sectionIdx != -1) {
            terrainAreana.free(terrainDataLocation.remove(key));
            regionManager.removeSectionIndex(uploadStream, sectionIdx);
            //Clear the segment
            long segment = uploadStream.getUpload(sectionBuffer, (long) sectionIdx * SECTION_SIZE, SECTION_SIZE);
            MemoryUtil.memSet(segment, 0, SECTION_SIZE);
        }
    }

    public void commitChanges() {
        uploadStream.commit();
    }

    public void delete() {
        uploadStream.delete();
        sectionBuffer.delete();
        terrainAreana.delete();
        regionManager.delete();
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public long getSectionDataAddress() {
        return sectionBuffer.getDeviceAddress();
    }

    public int getSectionRegionIndex(int x, int y, int z) {
        return sectionOffset.getOrDefault(getSectionKey(x,y,z), -1);
    }
}



