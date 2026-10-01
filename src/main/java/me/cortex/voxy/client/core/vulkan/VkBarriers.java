package me.cortex.voxy.client.core.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.*;

//Translations of the glMemoryBarrier calls voxy uses (see ARCHITECTURE_NOTES.md section 5)
public final class VkBarriers {
    //Every read access except INDIRECT_COMMAND_READ: having that bit in a barriers destination mask costs ~2us per barrier
    // on nvidia (measured, it flushes the command processors caches), MEMORY_READ implies it, so it is only ever added
    // by barriers in front of an indirect draw/dispatch that consumes a buffer written on the gpu.
    private static final long READS = VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
            | VK_ACCESS_2_UNIFORM_READ_BIT | VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT
            | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_INPUT_ATTACHMENT_READ_BIT
            | VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_2_INDEX_READ_BIT;
    private static final long WRITES = VK_ACCESS_2_SHADER_WRITE_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT
            | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    private static final long SHADER_STAGES = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;

    //Per frame counters for the benchmark (cheap, plain ints)
    public static final String[] TYPES = {"full", "shaderStorage", "shaderStorageAndCommand", "transferToAll", "allToTransfer", "transferToHost", "attachmentToSampled", "storage/custom"};
    public static final long[] typeTotals = new long[TYPES.length];
    private static final int[] typeCounts = new int[TYPES.length];
    public static int barrierCount, passCount;
    public static long barrierTotal, passTotal;
    public static long barrierFrames;

    public static void frameDone() {
        for (int i = 0; i < typeCounts.length; i++) { typeTotals[i] += typeCounts[i]; typeCounts[i] = 0; }
        barrierTotal += barrierCount;
        passTotal += passCount;
        barrierFrames++;
        barrierCount = 0;
        passCount = 0;
    }

    public static double averagePerFrame() {
        return barrierFrames == 0 ? 0 : (double) barrierTotal / barrierFrames;
    }

    public static double passesPerFrame() {
        return barrierFrames == 0 ? 0 : (double) passTotal / barrierFrames;
    }

    public static void resetStats() {
        passTotal = 0;
        java.util.Arrays.fill(typeTotals, 0);
        barrierTotal = 0;
        barrierFrames = 0;
    }

    public static void barrier(VkCommandBuffer cmd, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        barrierCount++;
        try (var stack = MemoryStack.stackPush()) {
            var mb = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(srcStage)
                    .srcAccessMask(srcAccess)
                    .dstStageMask(dstStage)
                    .dstAccessMask(dstAccess);
            var dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(mb);
            vkCmdPipelineBarrier2KHR(cmd, dep);
        }
    }

    //Same as the barrier minecraft records between all of its own operations
    public static void full(VkCommandBuffer cmd) {
        typeCounts[0]++;
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, READS | WRITES);
    }

    //GL_SHADER_STORAGE_BARRIER_BIT
    public static void shaderStorage(VkCommandBuffer cmd) {
        typeCounts[1]++;
        barrier(cmd, SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    //GL_SHADER_STORAGE_BARRIER_BIT|GL_UNIFORM_BARRIER_BIT, no indirect command access (that bit is expensive)
    public static void shaderStorageAndUniform(VkCommandBuffer cmd) {
        typeCounts[1]++;
        barrier(cmd, SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_UNIFORM_READ_BIT);
    }

    //GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT(|GL_UNIFORM_BARRIER_BIT): only in front of an indirect consumer
    public static void shaderStorageAndCommand(VkCommandBuffer cmd) {
        typeCounts[2]++;
        barrier(cmd, SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                SHADER_STAGES | VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_UNIFORM_READ_BIT);
    }

    //Compute storage writes -> compute/fragment sampled reads (and storage access)
    public static void storageToSampled(VkCommandBuffer cmd) {
        typeCounts[7]++;
        barrier(cmd, SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                SHADER_STAGES, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    //Compute storage writes -> everything that may read them later (no indirect command access)
    public static void storageToAll(VkCommandBuffer cmd) {
        typeCounts[7]++;
        barrier(cmd, SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, READS | WRITES);
    }

    //GL_BUFFER_UPDATE_BARRIER_BIT: transfer writes (copies/fills) -> anything. indirect: whether one of the written
    // buffers is consumed by an indirect draw/dispatch (see VkGpuBuffer.indirect)
    public static void transferToAll(VkCommandBuffer cmd) {
        transferToAll(cmd, false);
    }

    public static void transferToAll(VkCommandBuffer cmd, boolean indirect) {
        typeCounts[3]++;
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, READS | WRITES | (indirect ? VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT : 0));
    }

    //Anything -> transfer (before overwriting/reading with copies). Reads only need an execution dependency
    public static void allToTransfer(VkCommandBuffer cmd) {
        typeCounts[4]++;
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT);
    }

    //GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT: copy into host visible memory -> host read
    public static void transferToHost(VkCommandBuffer cmd) {
        typeCounts[5]++;
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT);
    }

    //GL_FRAMEBUFFER_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT / glTextureBarrier: attachment writes -> sampling
    public static void attachmentToSampled(VkCommandBuffer cmd) {
        typeCounts[6]++;
        barrier(cmd, VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                SHADER_STAGES | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                        | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                        | VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
    }

    private VkBarriers() {}
}
