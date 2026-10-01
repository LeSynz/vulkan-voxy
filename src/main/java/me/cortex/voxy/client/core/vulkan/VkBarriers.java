package me.cortex.voxy.client.core.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.*;

//Translations of the glMemoryBarrier calls voxy uses (see ARCHITECTURE_NOTES.md section 5)
public final class VkBarriers {
    private static final long SHADER_STAGES = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;

    public static void barrier(VkCommandBuffer cmd, long srcStage, long srcAccess, long dstStage, long dstAccess) {
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
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    //GL_SHADER_STORAGE_BARRIER_BIT
    public static void shaderStorage(VkCommandBuffer cmd) {
        barrier(cmd, SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    //GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT(|GL_UNIFORM_BARRIER_BIT)
    public static void shaderStorageAndCommand(VkCommandBuffer cmd) {
        barrier(cmd, SHADER_STAGES, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                SHADER_STAGES | VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_UNIFORM_READ_BIT);
    }

    //GL_BUFFER_UPDATE_BARRIER_BIT: transfer writes (copies/fills) -> anything
    public static void transferToAll(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    //Anything -> transfer (before overwriting/reading with copies)
    public static void allToTransfer(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT | VK_ACCESS_2_MEMORY_READ_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT);
    }

    //GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT: copy into host visible memory -> host read
    public static void transferToHost(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT);
    }

    //GL_FRAMEBUFFER_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT / glTextureBarrier: attachment writes -> sampling
    public static void attachmentToSampled(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                SHADER_STAGES | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                        | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                        | VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
    }

    private VkBarriers() {}
}
