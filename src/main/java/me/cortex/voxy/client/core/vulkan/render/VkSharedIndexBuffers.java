package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.core.rendering.util.IndexData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;

//Vulkan versions of SharedIndexBuffer's index buffers. GL uses unsigned byte indices for the cube and fullscreen
// draws, these are widened to 16 bit here.
public final class VkSharedIndexBuffers {
    public static final int QUAD_COUNT = 16380;
    //u16 quad indices (SharedIndexBuffer.INSTANCE without the trailing byte cube)
    public final VkGpuBuffer quads;
    //u16, 36 indices of a cube (the byte cube at SharedIndexBuffer.CUBE_INDEX_OFFSET)
    public final VkGpuBuffer cube;
    //u16, the first byte quad of SharedIndexBuffer.INSTANCE_BYTE (6 indices, fullscreen blits)
    public final VkGpuBuffer quad;
    //u16, 32 cubes of 8 vertices each (SharedIndexBuffer.INSTANCE_BB_BYTE)
    public final VkGpuBuffer boundingBoxCubes;

    public VkSharedIndexBuffers() {
        this.quads = upload(IndexData.generateQuadIndicesShort(QUAD_COUNT), "QuadIndices");
        this.cube = upload(IndexData.widenBytesToShorts(IndexData.generateCubeIndexBuffer()), "CubeIndices");
        this.quad = upload(IndexData.widenBytesToShorts(IndexData.generateQuadIndicesByte(1)), "FullscreenIndices");
        this.boundingBoxCubes = upload(IndexData.widenBytesToShorts(IndexData.generateByteCubesIndexBuffer(256/8)), "BoundingBoxIndices");
        UploadStream.INSTANCE.commit();
    }

    private static VkGpuBuffer upload(me.cortex.voxy.common.util.MemoryBuffer data, String name) {
        var buffer = new VkGpuBuffer(data.size, false).name(name);
        UploadStream.INSTANCE.upload(buffer, 0, data);
        data.free();
        return buffer;
    }

    public void free() {
        this.quads.free();
        this.cube.free();
        this.quad.free();
        this.boundingBoxCubes.free();
    }
}
