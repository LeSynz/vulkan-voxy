package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.backend.IGpuBuffer;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.section.geometry.ISectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.VkBarriers;
import me.cortex.voxy.client.core.vulkan.VkContext;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;
import me.cortex.voxy.client.core.vulkan.VkUploadStream;
import me.cortex.voxy.client.core.vulkan.shader.VkComputePipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

//Vulkan implementation: same compute shaders, reading straight out of the mapped upload buffer
public class VkNodeGpuWriter implements INodeGpuWriter {
    public static VkShaderBuilder scatterShader() {
        return VkShaderBuilder.make()
                .define("INPUT_BUFFER_BINDING", 0)
                .define("OUTPUT_BUFFER1_BINDING", 1)
                .define("OUTPUT_BUFFER2_BINDING", 2)
                .add(ShaderType.COMPUTE, "voxy:util/scatter.comp");
    }

    public static VkShaderBuilder memcpyShader() {
        return VkShaderBuilder.make()
                .define("INPUT_HEADER_BUFFER_BINDING", 0)
                .define("INPUT_DATA_BUFFER_BINDING", 1)
                .define("OUTPUT_BUFFER_BINDING", 2)
                .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp");
    }

    private final VkComputePipeline scatterWrite = scatterShader().compileCompute();
    private final VkComputePipeline multiMemcpy = memcpyShader().compileCompute();

    private static long uploadBuffer() {
        return ((VkUploadStream) UploadStream.INSTANCE).getBuffer();
    }

    @Override
    public void uploadGeometry(ISectionGeometryData geometry, long headerAddr, int copies, long dataAddr, int scratchSize) {
        int upCopies = UploadStream.alignUpAlloc(copies*16);
        int upScratchSize = UploadStream.alignUpAlloc(scratchSize);
        long ptr = UploadStream.INSTANCE.rawUploadAddress(upScratchSize + upCopies);
        UnsafeUtil.memcpy(headerAddr, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
        UnsafeUtil.memcpy(dataAddr, UploadStream.INSTANCE.getBaseAddress() + ptr + upCopies, scratchSize);
        UploadStream.INSTANCE.commit();//Flushes the mapped range

        var cmd = VkContext.get().stream.cmd();
        this.multiMemcpy
                .ssboRaw(0, uploadBuffer(), ptr, upCopies)
                .ssboRaw(1, uploadBuffer(), ptr + upCopies, upScratchSize)
                .ssbo(2, (VkGpuBuffer) geometry.getGeometryBuffer());
        VkBarriers.shaderStorage(cmd);
        this.multiMemcpy.bind(cmd);
        this.multiMemcpy.dispatch(cmd, copies, 1, 1);
        VkBarriers.shaderStorage(cmd);
    }

    @Override
    public void scatterWrite(ISectionGeometryData geometry, IGpuBuffer nodeBuffer, long srcAddr, int count) {
        int chunks = (count+3)/4;
        int streamSize = chunks*80;
        long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);
        MemoryUtil.memCopy(srcAddr, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
        UploadStream.INSTANCE.commit();

        var cmd = VkContext.get().stream.cmd();
        this.scatterWrite
                .ssboRaw(0, uploadBuffer(), ptr, UploadStream.alignUpAlloc(streamSize))
                .ssbo(1, (VkGpuBuffer) nodeBuffer)
                .ssbo(2, (VkGpuBuffer) geometry.getMetadataBuffer());
        VkBarriers.shaderStorage(cmd);
        this.scatterWrite.bind(cmd);
        this.scatterWrite.pushConstants(cmd, count);
        this.scatterWrite.dispatch(cmd, (count+127)/128, 1, 1);
        VkBarriers.shaderStorageAndUniform(cmd);
    }

    @Override
    public void free() {
        this.scatterWrite.free();
        this.multiMemcpy.free();
    }
}
