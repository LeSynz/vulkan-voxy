package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.backend.IGpuBuffer;
import me.cortex.voxy.client.core.rendering.section.geometry.ISectionGeometryData;

//The gpu side of AsyncNodeManager's render thread sync: geometry copies and node/metadata scatter writes
public interface INodeGpuWriter {
    //headerAddr: copies*16 bytes of uvec4 copy jobs, dataAddr: scratchSize bytes of quad data
    void uploadGeometry(ISectionGeometryData geometry, long headerAddr, int copies, long dataAddr, int scratchSize);

    //srcAddr: ceil(count/4)*80 bytes of scatter write chunks
    void scatterWrite(ISectionGeometryData geometry, IGpuBuffer nodeBuffer, long srcAddr, int count);

    void free();
}
