package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.backend.IGpuBuffer;

//Backend independent part of the node cleaner (GlNodeCleaner/VkNodeCleaner implement the gpu side).
//Uses compute shaders to compute the last 256 rendered section (64x64 workgroup size maybe)
// done via warp level sort, then workgroup sort (shared memory), (/w sorting network)
// then use bubble sort (/w fast path going to middle or 2 subdivisions deep) the bubble it up
// can do incremental sorting pass aswell, so only scan and sort a rolling sector of sections
// (over a few frames to not cause lag, maybe)
public abstract class NodeCleaner {
    protected static final int SORTING_WORKER_SIZE = 64;
    protected static final int WORK_PER_THREAD = 8;
    static final int OUTPUT_COUNT = 256;

    protected final AsyncNodeManager nodeManager;
    int visibilityId = 0;

    protected NodeCleaner(AsyncNodeManager nodeManager) {
        this.nodeManager = nodeManager;
    }

    public abstract void tick(IGpuBuffer nodeDataBuffer);

    public abstract void updateIds(IntOpenHashSet collection);

    public abstract void free();

    public abstract IGpuBuffer getVisibilityBuffer();

    public int getVisibilityId() {
        return this.visibilityId;
    }

    protected boolean shouldCleanGeometry() {
        if (false) {
            //If used more than 75% of geometry buffer
            long used = this.nodeManager.getUsedGeometryCapacity();
            return 3 < ((double) used) / ((double) (this.nodeManager.getGeometryCapacity() - used));
        } else {
            long remaining = this.nodeManager.getGeometryCapacity() - this.nodeManager.getUsedGeometryCapacity();
            return remaining < 256_000_000;//If less than 256 mb free memory
        }
    }
}
