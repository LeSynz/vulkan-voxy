package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.backend.IGpuBuffer;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.VkBarriers;
import me.cortex.voxy.client.core.vulkan.VkContext;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;
import me.cortex.voxy.client.core.vulkan.VkUploadStream;
import me.cortex.voxy.client.core.vulkan.shader.VkComputePipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import org.lwjgl.system.MemoryUtil;

//Vulkan implementation of the node cleaner (same compute shaders as GlNodeCleaner)
public class VkNodeCleaner extends NodeCleaner {
    public static VkShaderBuilder sorterShader() {
        return VkShaderBuilder.make()
                .define("WORK_SIZE", SORTING_WORKER_SIZE)
                .define("ELEMS_PER_THREAD", WORK_PER_THREAD)
                .define("OUTPUT_SIZE", OUTPUT_COUNT)
                .define("VISIBILITY_BUFFER_BINDING", 1)
                .define("OUTPUT_BUFFER_BINDING", 2)
                .define("NODE_DATA_BINDING", 3)
                .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/sort_visibility.comp");
    }

    public static VkShaderBuilder resultTransformerShader() {
        return VkShaderBuilder.make()
                .define("OUTPUT_SIZE", OUTPUT_COUNT)
                .define("MIN_ID_BUFFER_BINDING", 0)
                .define("NODE_BUFFER_BINDING", 1)
                .define("OUTPUT_BUFFER_BINDING", 2)
                .define("VISIBILITY_BUFFER_BINDING", 3)
                .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/result_transformer.comp");
    }

    public static VkShaderBuilder batchClearShader() {
        return VkShaderBuilder.make()
                .define("VISIBILITY_BUFFER_BINDING", 0)
                .define("LIST_BUFFER_BINDING", 1)
                .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/batch_visibility_set.comp");
    }

    private final VkComputePipeline sorter = sorterShader().compileCompute();
    private final VkComputePipeline resultTransformer = resultTransformerShader().compileCompute();
    private final VkComputePipeline batchClear = batchClearShader().compileCompute();

    final VkGpuBuffer visibilityBuffer;
    private final VkGpuBuffer outputBuffer = new VkGpuBuffer(OUTPUT_COUNT*4+OUTPUT_COUNT*8);//Scratch + output

    public VkNodeCleaner(AsyncNodeManager nodeManager) {
        super(nodeManager);
        this.visibilityBuffer = new VkGpuBuffer(nodeManager.maxNodeCount*4L, false);
        this.visibilityBuffer.fill(-1);

        this.batchClear
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer);

        this.sorter
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("OUTPUT_BUFFER_BINDING", this.outputBuffer);
    }

    @Override
    public void tick(IGpuBuffer nodeDataBuffer0) {
        var nodeDataBuffer = (VkGpuBuffer) nodeDataBuffer0;
        this.visibilityId++;
        if (this.shouldCleanGeometry()) {
            this.outputBuffer.fill(this.nodeManager.maxNodeCount - 2);
            var cmd = VkContext.get().stream.cmd();

            this.sorter.ssbo(3, nodeDataBuffer);
            VkBarriers.shaderStorage(cmd);
            this.sorter.bind(cmd);
            this.sorter.dispatch(cmd, (this.nodeManager.getCurrentMaxNodeId() + (SORTING_WORKER_SIZE*WORK_PER_THREAD) - 1) / (SORTING_WORKER_SIZE*WORK_PER_THREAD), 1, 1);

            this.resultTransformer
                    .ssbo(0, this.outputBuffer, 0, 4 * OUTPUT_COUNT)
                    .ssbo(1, nodeDataBuffer)
                    .ssbo(2, this.outputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT)
                    .ssbo(3, this.visibilityBuffer);
            VkBarriers.shaderStorage(cmd);
            this.resultTransformer.bind(cmd);
            this.resultTransformer.pushConstants(cmd, this.visibilityId);
            this.resultTransformer.dispatch(cmd, 1, 1, 1);
            VkBarriers.shaderStorage(cmd);

            DownloadStream.INSTANCE.download(this.outputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT,
                    buffer -> this.nodeManager.submitRemoveBatch(buffer.copy())//Copy into buffer and emit to node manager
            );
        }
    }

    @Override
    public void updateIds(IntOpenHashSet collection) {
        if (!collection.isEmpty()) {
            int count = collection.size();
            long addr = UploadStream.INSTANCE.rawUploadAddress(count*4);//Internally does upsizing alignement

            long ptr = UploadStream.INSTANCE.getBaseAddress() + addr;
            var iter = collection.iterator();
            while (iter.hasNext()) {
                MemoryUtil.memPutInt(ptr, iter.nextInt()); ptr+=4;
            }
            UploadStream.INSTANCE.commit();

            var cmd = VkContext.get().stream.cmd();
            this.batchClear.ssboRaw(1, ((VkUploadStream) UploadStream.INSTANCE).getBuffer(), addr, UploadStream.alignUpAlloc(count*4));
            VkBarriers.shaderStorage(cmd);
            this.batchClear.bind(cmd);
            this.batchClear.pushConstants(cmd, count, this.visibilityId);
            this.batchClear.dispatch(cmd, (count+127)/128, 1, 1);
            VkBarriers.shaderStorage(cmd);
        }
    }

    @Override
    public VkGpuBuffer getVisibilityBuffer() {
        return this.visibilityBuffer;
    }

    @Override
    public void free() {
        this.sorter.free();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
        this.batchClear.free();
        this.resultTransformer.free();
    }
}
