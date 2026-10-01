package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.vulkan.VkContext;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;
import me.cortex.voxy.client.core.vulkan.VkOutOfMemoryException;
import me.cortex.voxy.common.Logger;

//Vulkan section metadata + geometry store. No sparse buffers on vulkan: the geometry buffer is a single device
// local allocation, sized from the device limits and shrunk on allocation failure.
public class VkSectionGeometryData implements ISectionGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    private static final long MIN_GEOMETRY_SIZE = 512L*1024*1024;

    private final VkGpuBuffer sectionMetadataBuffer;
    private final VkGpuBuffer geometryBuffer;
    private final int maxSectionCount;
    private int currentSectionCount;

    public VkSectionGeometryData(int maxSectionCount) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new VkGpuBuffer((long) maxSectionCount * SECTION_METADATA_SIZE).name("SectionMetadata");

        long capacity = targetGeometryCapacity();
        long start = System.currentTimeMillis();
        VkGpuBuffer buffer = null;
        while (buffer == null) {
            Logger.info("Creating " + (capacity/(1024*1024)) + "MB vulkan geometry buffer");
            try {
                buffer = new VkGpuBuffer(capacity, false);
            } catch (VkOutOfMemoryException e) {
                if (capacity <= MIN_GEOMETRY_SIZE) {
                    throw e;
                }
                Logger.warn("Failed to allocate geometry buffer of " + (capacity/(1024*1024)) + "MB, retrying smaller");
                capacity = Math.max(MIN_GEOMETRY_SIZE, (capacity/2)&~7L);
            }
        }
        this.geometryBuffer = buffer.name("Geometry");
        Logger.info("Successfully allocated the geometry buffer in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static long targetGeometryCapacity() {
        var ctx = VkContext.get();
        //Must be bindable as a single storage buffer and be a single allocation
        long capacity = Math.min(ctx.maxStorageBufferRange, ctx.maxMemoryAllocationSize);
        capacity = Math.min(capacity, (1L<<32)-1024);
        if (ctx.deviceLocalHeapSize > 0) {
            //Leave room for minecraft and everything else on the gpu (no memory budget query available)
            capacity = Math.min(capacity, Math.max(MIN_GEOMETRY_SIZE, ctx.deviceLocalHeapSize - (long)(2.5*1024*1024*1024)));
        }
        capacity = Math.max(MIN_GEOMETRY_SIZE, capacity);
        var override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            capacity = Long.parseLong(override)*1024L*1024L;
        }
        return capacity&~7L;//8 bytes per quad
    }

    @Override
    public VkGpuBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    @Override
    public VkGpuBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    @Override
    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    @Override
    public int getSectionCount() {
        return this.currentSectionCount;
    }

    @Override
    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    @Override
    public long getGeometryCapacityBytes() {
        return this.geometryBuffer.size();
    }

    @Override
    public void ensureAccessable(int maxElementAccess) {
        //Not sparse, always fully backed
    }

    @Override
    public long getMaxCapacity() {
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        this.sectionMetadataBuffer.free();
        this.geometryBuffer.free();
    }
}
