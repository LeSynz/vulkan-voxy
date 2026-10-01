package me.cortex.voxy.client.core.backend;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;

//Creates buffers for the active backend, for use by shared (cpu side) systems
public final class GpuBuffers {
    //Zero initialized, same as new GlBuffer(size)
    public static IGpuBuffer create(long size) {
        if (GpuBackend.isVulkan()) {
            return new VkGpuBuffer(size);
        }
        return new GlBuffer(size);
    }

    public static IGpuBuffer createUninitialized(long size) {
        if (GpuBackend.isVulkan()) {
            return new VkGpuBuffer(size, false);
        }
        return new GlBuffer(size, false);
    }

    //Initialized with the contents of data (same as new GlBuffer(MemoryBuffer))
    public static IGpuBuffer create(MemoryBuffer data) {
        if (GpuBackend.isVulkan()) {
            var buffer = new VkGpuBuffer(data.size, false);
            UploadStream.INSTANCE.upload(buffer, 0, data);
            UploadStream.INSTANCE.commit();
            return buffer;
        }
        return new GlBuffer(data);
    }

    private GpuBuffers() {}
}
