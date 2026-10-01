package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.backend.IGpuBuffer;

//Section metadata + quad geometry store, implemented by BasicSectionGeometryData (GL) and VkSectionGeometryData
public interface ISectionGeometryData extends IGeometryData {
    IGpuBuffer getGeometryBuffer();
    IGpuBuffer getMetadataBuffer();
    void setSectionCount(int count);
    int getMaxSectionCount();
    long getGeometryCapacityBytes();
    //Ensures geometry upto the element index is backed by memory (sparse buffers)
    void ensureAccessable(int maxElementAccess);
}
