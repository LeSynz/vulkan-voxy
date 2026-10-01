package me.cortex.voxy.client.core.backend;

import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;

//Creates gpu buffers for shared (cpu side) systems
public final class GpuBuffers {
    //Zero initialized
    public static IGpuBuffer create(long size) {
        return new VkGpuBuffer(size);
    }

    public static IGpuBuffer createUninitialized(long size) {
        return new VkGpuBuffer(size, false);
    }

    //Initialized with the contents of data
    public static IGpuBuffer create(MemoryBuffer data) {
        var buffer = new VkGpuBuffer(data.size, false);
        UploadStream.INSTANCE.upload(buffer, 0, data);
        UploadStream.INSTANCE.commit();
        return buffer;
    }

    private GpuBuffers() {}
}
