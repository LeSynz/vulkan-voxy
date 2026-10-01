package me.cortex.voxy.client.core.vulkan.render;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.*;
import me.cortex.voxy.client.core.vulkan.shader.VkComputePipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

import static org.lwjgl.vulkan.VK10.vkCmdUpdateBuffer;

//Vulkan port of HierarchicalOcclusionTraverser (same traversal compute shader, MAX_ITERATIONS indirect dispatches)
public class VkHierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxy.hierarchicalShaderDebug", "false").equals("true");
    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_QUEUE_SIZE = 200_000;

    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER+1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private final VkGpuBuffer requestBuffer;
    private final VkGpuBuffer nodeBuffer;
    private final VkGpuBuffer uniformBuffer = new VkGpuBuffer(1024).name("TraversalUniform");
    private final VkGpuBuffer statisticsBuffer = new VkGpuBuffer(1024);

    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];
    private final VkGpuBuffer topNodeIds = new VkGpuBuffer(MAX_QUEUE_SIZE*4).name("TopNodeIds");
    private final VkGpuBuffer queueMetaBuffer = new VkGpuBuffer(4*4*MAX_ITERATIONS).name("QueueMeta");
    private final VkGpuBuffer scratchQueueA = new VkGpuBuffer(MAX_QUEUE_SIZE*4).name("ScratchQueueA");
    private final VkGpuBuffer scratchQueueB = new VkGpuBuffer(MAX_QUEUE_SIZE*4).name("ScratchQueueB");

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;
    private static final int STATISTICS_BUFFER_BINDING = BINDING_COUNTER++;

    private final VkComputePipeline traversal;

    public static VkShaderBuilder traversalShader(RenderProperties properties, boolean statistics) {
        return VkShaderBuilder.make()
                .apply(properties::apply)
                .defineIf("DEBUG", HIERARCHICAL_SHADER_DEBUG)
                .define("MAX_ITERATIONS", MAX_ITERATIONS)
                .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)
                .define("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE)
                .define("HIZ_BINDING", 0)
                .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
                .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
                .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
                .define("NODE_DATA_BINDING", NODE_DATA_BINDING)
                .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
                .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
                .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
                .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)
                .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)
                .defineIf("HAS_STATISTICS", statistics)
                .defineIf("STATISTICS_BUFFER_BINDING", statistics, STATISTICS_BUFFER_BINDING)
                .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/traversal_dev.comp");
    }

    public VkHierarchicalOcclusionTraverser(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;
        this.requestBuffer = new VkGpuBuffer(MAX_REQUEST_QUEUE_SIZE*8L+8).name("RequestQueue");
        this.nodeBuffer = new VkGpuBuffer(nodeManager.maxNodeCount*16L, false).name("NodeData").fill(-1);

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);

        this.traversal = traversalShader(properties, RenderStatistics.enabled).compileCompute();
        this.traversal
                .ubo("SCENE_UNIFORM_BINDING", this.uniformBuffer)
                .ssbo("REQUEST_QUEUE_BINDING", this.requestBuffer)
                .ssbo("NODE_DATA_BINDING", this.nodeBuffer)
                .ssbo("NODE_QUEUE_META_BINDING", this.queueMetaBuffer)
                .ssbo("RENDER_TRACKER_BINDING", (VkGpuBuffer) this.nodeCleaner.getVisibilityBuffer())
                .ssboIf("STATISTICS_BUFFER_BINDING", this.statisticsBuffer);
    }

    //Single int write (GL used glClearNamedBufferSubData)
    private static void writeInt(VkGpuBuffer buffer, long offset, int value) {
        var cmd = VkContext.get().stream.cmd();
        VkBarriers.allToTransfer(cmd);
        try (var stack = MemoryStack.stackPush()) {
            vkCmdUpdateBuffer(cmd, buffer.buffer, offset, stack.ints(value));
        }
        VkBarriers.transferToAll(cmd);
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;
        if (this.topNodeCount > this.topNodeIds.size()/4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }
        writeInt(this.topNodeIds, aid * 4L, id);
        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        int idx = this.topNode2idxMapping.remove(id);
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException();
        }
        if (idx == this.topNodeCount) {
            return;
        }
        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1)
            throw new IllegalStateException();
        writeInt(this.topNodeIds, idx*4L, endTLNId);
    }

    private void uploadUniform(VkViewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);

        viewport.MVP.getToAddress(ptr); ptr += 4*4*4;
        viewport.section.getToAddress(ptr); ptr += 4*3;
        MemoryUtil.memPutInt(ptr, viewport.hiz.getPackedLevels()); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize*VoxyConfig.CONFIG.subDivisionSize;
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) /(viewport.width*viewport.height)); ptr += 4;

        for (int i = 0; i < 6; i++) {
            viewport.frustumPlanes[i].getToAddress(ptr); ptr += 4*4;
        }

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size()/4-1)); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.getVisibilityId()); ptr += 4;
        {
            final double TARGET_COUNT = 4000;
            double iFillness = Math.max(0, (TARGET_COUNT - this.meshGen.getTaskCount()) / TARGET_COUNT);
            iFillness = Math.pow(iFillness, 2);
            final int requestSize = (int) Math.ceil(iFillness * MAX_REQUEST_QUEUE_SIZE);
            MemoryUtil.memPutInt(ptr, Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize)));ptr += 4;
        }
        MemoryUtil.memPutFloat(ptr, (float) Math.pow(VoxyConfig.CONFIG.sectionRenderDistance*16*32,2));ptr += 4;
    }

    public void doTraversal(VkViewport viewport) {
        this.uploadUniform(viewport);
        var cmd = VkContext.get().stream.cmd();

        if (RenderStatistics.enabled) {
            this.statisticsBuffer.zero();
        }

        //Clear the render output counter
        viewport.getRenderList().zeroRange(0, 4);

        this.traversal
                .texture(0, viewport.hiz.getHizView(), VkSamplers.nearestMipNearestClamp())
                .ssbo(RENDER_QUEUE_BINDING, viewport.getRenderList());

        this.traverseInternal(cmd);

        this.downloadResetRequestQueue();

        if (RenderStatistics.enabled) {
            DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(down.address+i*4L);
                }
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(down.address+MAX_ITERATIONS*4L+i*4L);
                }
            });
        }
    }

    private void traverseInternal(VkCommandBuffer cmd) {
        int firstDispatchSize = (this.topNodeCount+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;
        {
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            UploadStream.INSTANCE.commit();
        }

        //First iteration reads the top level node list
        this.traversal
                .ssbo(NODE_QUEUE_SOURCE_BINDING, this.topNodeIds)
                .ssbo(NODE_QUEUE_SINK_BINDING, this.scratchQueueB);
        VkBarriers.shaderStorageAndCommand(cmd);
        this.traversal.bind(cmd);
        this.traversal.pushConstants(cmd, 0);
        this.traversal.dispatch(cmd, firstDispatchSize, 1, 1);
        VkBarriers.shaderStorageAndCommand(cmd);

        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            //Flipflop buffers
            this.traversal
                    .ssbo(NODE_QUEUE_SOURCE_BINDING, (iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB)
                    .ssbo(NODE_QUEUE_SINK_BINDING, (iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA);
            this.traversal.pushDescriptors(cmd);
            this.traversal.pushConstants(cmd, iter);
            this.traversal.dispatchIndirect(cmd, this.queueMetaBuffer.buffer, iter * 4L * 4);
            VkBarriers.shaderStorageAndCommand(cmd);
        }
    }

    private void downloadResetRequestQueue() {
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        this.requestBuffer.zeroRange(0, 4);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);ptr += 8;
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size()>>3)-1) {
            count = (int) ((this.requestBuffer.size()>>3)-1);
        }
        if (count != 0) {
            var buffer = new MemoryBuffer(count*8L+8).cpyFrom(ptr-8);
            MemoryUtil.memPutInt(buffer.address, count);
            this.nodeManager.submitRequestBatch(buffer);
        }
    }

    public VkGpuBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public void free() {
        this.traversal.free();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.statisticsBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
    }

    public void addDebug(List<String> debug) {
        if (this.topNodeCount>this.idx2topNodeMapping.length/2) {
            debug.add("TLN#: " + this.topNodeCount);
        }
    }
}
