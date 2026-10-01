package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vulkan.Capabilities;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.model.VkModelStore;
import me.cortex.voxy.client.core.rendering.section.geometry.VkSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.*;
import me.cortex.voxy.client.core.vulkan.shader.VkComputePipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsPipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsState;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

//Vulkan port of MDICSectionRenderer: GPU driven culling + command generation, terrain drawn with
// vkCmdDrawIndexedIndirectCount (GL: glMultiDrawElementsIndirectCountARB)
public class VkSectionRenderer {
    public static final int OPAQUE_DRAW_COUNT = 400_000;
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;
    public static final int TEMPORAL_DRAW_COUNT = 100_000;
    private static final int TRANSLUCENT_OFFSET = OPAQUE_DRAW_COUNT;
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET+TRANSLUCENT_DRAW_COUNT;
    private static final int STATISTICS_BUFFER_BINDING = 8;
    private static final int DRAW_COMMAND_STRIDE = 5*4;

    private final RenderProperties properties;
    private final VkModelStore modelStore;
    private final VkSectionGeometryData geometryManager;
    private final IVkTerrainTargets targets;
    private final VkSharedIndexBuffers indices;

    private final VkGraphicsPipeline terrainShader;
    private final VkGraphicsPipeline translucentTerrainShader;
    private final VkGraphicsPipeline cullShader;
    private final VkComputePipeline commandGenShader;
    private final VkComputePipeline prepShader;
    private final VkComputePipeline prefixSumShader;
    private final VkComputePipeline translucentGenShader;

    private final VkGpuBuffer uniform = new VkGpuBuffer(1024).name("SectionUniform");
    private final VkGpuBuffer distanceCountBuffer = new VkGpuBuffer(1024*4+TRANSLUCENT_DRAW_COUNT*4).name("TranslucentDistanceCount");
    private final VkGpuBuffer statisticsBuffer = new VkGpuBuffer(1024);

    private final int maxDrawCount;

    // --- shader permutations (also used by the offline SPIR-V validation test)

    public static VkShaderBuilder terrainShader(RenderProperties properties, float[] faceTints, boolean translucent) {
        return VkShaderBuilder.make()
                .apply(properties::apply)
                .defineIf("TAA_PATCH", false)
                .defineIf("DEBUG_RENDER", false)
                .define("NO_SHADE_FACE_TINT", faceTints[0])
                .define("UP_FACE_TINT", faceTints[0])
                .define("DOWN_FACE_TINT", faceTints[1])
                .define("Z_AXIS_FACE_TINT", faceTints[2])
                .define("X_AXIS_FACE_TINT", faceTints[3])
                .defineIf("TRANSLUCENT", translucent)
                .add(ShaderType.VERTEX, "voxy:lod/gl46/quads3.vert")
                .add(ShaderType.FRAGMENT, "voxy:lod/gl46/quads.frag");
    }

    public static VkShaderBuilder cullShader(RenderProperties properties) {
        return VkShaderBuilder.make()
                .apply(properties::apply)
                .add(ShaderType.VERTEX, "voxy:lod/gl46/cull/raster.vert")
                .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag");
    }

    public static VkShaderBuilder commandGenShader(boolean statistics) {
        return VkShaderBuilder.make()
                .define("TRANSLUCENT_WRITE_BASE", 1024)
                .define("TEMPORAL_OFFSET", TEMPORAL_OFFSET)
                .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)
                .defineIf("HAS_STATISTICS", statistics)
                .defineIf("STATISTICS_BUFFER_BINDING", statistics, STATISTICS_BUFFER_BINDING)
                .add(ShaderType.COMPUTE, "voxy:lod/gl46/cmdgen.comp");
    }

    public static VkShaderBuilder prepShader() {
        return VkShaderBuilder.make()
                //Vulkan draws the cull cubes from their own u16 index buffer instead of the byte indices at
                // SharedIndexBuffer.CUBE_INDEX_OFFSET
                .define("CUBE_FIRST_INDEX", 0)
                .add(ShaderType.COMPUTE, "voxy:lod/gl46/prep.comp");
    }

    public static VkShaderBuilder prefixSumShader(boolean subgroup) {
        return VkShaderBuilder.make()
                .add(ShaderType.COMPUTE, subgroup?"voxy:util/prefixsum/inital3.comp":"voxy:util/prefixsum/simple.comp")
                .define("IO_BUFFER", 0);
    }

    public static VkShaderBuilder translucentGenShader() {
        return VkShaderBuilder.make()
                .add(ShaderType.COMPUTE, "voxy:lod/gl46/buildtranslucents.comp")
                .define("TRANSLUCENT_WRITE_BASE", 1024)
                .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
                .define("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET);
    }

    public static float[] faceTints() {
        var cardinalLight = Minecraft.getInstance().level.cardinalLighting();
        return new float[]{cardinalLight.up(), cardinalLight.down(), cardinalLight.north(), cardinalLight.east()};
    }

    public VkSectionRenderer(RenderProperties properties, IVkTerrainTargets targets, VkModelStore modelStore, VkSectionGeometryData geometryData, VkSharedIndexBuffers indices) {
        this.properties = properties;
        this.targets = targets;
        this.modelStore = modelStore;
        this.geometryManager = geometryData;
        this.indices = indices;

        int deviceMaxDraws = RenderSystemLimits.maxDrawIndirectCount();
        this.maxDrawCount = deviceMaxDraws;

        var tints = faceTints();
        var terrainState = VkGraphicsState.create()
                .colorFormats(targets.colourFormat())
                .depthFormat(targets.depthFormat())
                .depthTest(properties.vkCloserEqualDepthCompare())
                .depthWrite(true)
                //Voxy terrain only renders where there isnt vanilla terrain (stencil set up by the pipeline)
                .stencil(VK_COMPARE_OP_EQUAL, 1, 0xFF, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF);
        this.terrainShader = terrainShader(properties, tints, false).compileGraphics(terrainState);
        this.translucentTerrainShader = terrainShader(properties, tints, true).compileGraphics(terrainState.copy()
                .blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA));
        //The occlusion test only needs the depth/stencil attachment, the fragments just write the visibility buffer
        this.cullShader = cullShader(properties).compileGraphics(terrainState.copy()
                .depthWrite(false)
                .colorFormats());

        this.commandGenShader = commandGenShader(RenderStatistics.enabled).compileCompute();
        this.prepShader = prepShader().compileCompute();
        this.prefixSumShader = prefixSumShader(Capabilities.INSTANCE.subgroup).compileCompute();
        this.translucentGenShader = translucentGenShader().compileCompute();
    }

    private void uploadUniformBuffer(VkViewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 1024);

        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        if (viewport.frameId<0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId&0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        UploadStream.INSTANCE.commit();
    }

    private void bindRenderingBuffers(VkGraphicsPipeline shader, VkViewport viewport) {
        shader.ubo(0, this.uniform)
                .ssbo(1, this.geometryManager.getGeometryBuffer())
                .ssbo(2, this.geometryManager.getMetadataBuffer())
                .ssbo(3, this.modelStore.modelBuffer())
                .ssbo(4, this.modelStore.modelColourBuffer())
                .texture(0, this.modelStore.textures.sampleView, this.modelStore.blockSampler)
                .ssbo(5, viewport.positionScratchBuffer)
                .texture(1, lightmapView(), VkSamplers.linearClamp())
                .texture(2, viewport.depthBounding.getImage().sampleView, VkSamplers.nearestClamp());
    }

    private static long lightmapView() {
        return ((VulkanGpuTextureView) Minecraft.getInstance().gameRenderer.levelLightmap()).vkImageView();
    }

    private void renderTerrain(VkGraphicsPipeline shader, boolean translucent, VkViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount) {
        var cmd = VkContext.get().stream.cmd();
        this.bindRenderingBuffers(shader, viewport);
        VkBarriers.shaderStorageAndCommand(cmd);
        VkBarriers.attachmentToSampled(cmd);
        if (translucent) {
            this.targets.beginTranslucent(cmd, viewport);
        } else {
            this.targets.beginOpaque(cmd, viewport);
        }
        shader.bind(cmd);
        vkCmdBindIndexBuffer(cmd, this.indices.quads.buffer, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexedIndirectCount(cmd, viewport.drawCallBuffer.buffer, indirectOffset, viewport.drawCountCallBuffer.buffer, drawCountOffset, Math.min(maxDrawCount, this.maxDrawCount), DRAW_COMMAND_STRIDE);
        VkRendering.end(cmd);
    }

    public void renderOpaque(VkViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.uploadUniformBuffer(viewport);
        this.renderTerrain(this.terrainShader, false, viewport, 0, 4*3, Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), OPAQUE_DRAW_COUNT));
    }

    public void renderTemporal(VkViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.renderTerrain(this.terrainShader, false, viewport, TEMPORAL_OFFSET*5*4, 4*5, Math.min(this.geometryManager.getSectionCount(), TEMPORAL_DRAW_COUNT));
    }

    public void renderTranslucent(VkViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.renderTerrain(this.translucentTerrainShader, true, viewport, TRANSLUCENT_OFFSET*5*4, 4*4, Math.min(this.geometryManager.getSectionCount(), TRANSLUCENT_DRAW_COUNT));
    }

    public void buildDrawCalls(VkViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.uploadUniformBuffer(viewport);
        var cmd = VkContext.get().stream.cmd();

        {//Dispatch prep
            this.prepShader
                    .ubo(0, this.uniform)
                    .ssbo(1, viewport.drawCountCallBuffer)
                    .ssbo(2, viewport.getRenderList());
            VkBarriers.shaderStorage(cmd);
            this.prepShader.bind(cmd);
            this.prepShader.dispatch(cmd, 1, 1, 1);
            VkBarriers.shaderStorageAndCommand(cmd);
        }
        VkGpuTimer.mark(cmd, "dc prep");

        {//Test occlusion (rasterize section AABBs against the opaque depth, fragments mark the section visible)
            this.cullShader
                    .ubo(0, this.uniform)
                    .ssbo(1, this.geometryManager.getMetadataBuffer())
                    .ssbo(2, viewport.visibilityBuffer)
                    .ssbo(3, viewport.indirectLookupBuffer);
            this.targets.beginDepthOnly(cmd, viewport);
            this.cullShader.bind(cmd);
            vkCmdBindIndexBuffer(cmd, this.indices.cube.buffer, 0, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCountCallBuffer.buffer, 6*4, 1, DRAW_COMMAND_STRIDE);
            VkRendering.end(cmd);
            VkBarriers.shaderStorageAndUniform(cmd);
        }
        VkGpuTimer.mark(cmd, "dc cull raster");

        {//Generate the commands
            this.distanceCountBuffer.zeroRange(0, 1024*4);
            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zero();
                this.commandGenShader.ssbo(STATISTICS_BUFFER_BINDING, this.statisticsBuffer);
            }
            this.commandGenShader
                    .ubo(0, this.uniform)
                    .ssbo(1, viewport.drawCallBuffer)
                    .ssbo(2, viewport.drawCountCallBuffer)
                    .ssbo(3, this.geometryManager.getMetadataBuffer())
                    .ssbo(4, viewport.visibilityBuffer)
                    .ssbo(5, viewport.indirectLookupBuffer)
                    .ssbo(6, viewport.positionScratchBuffer)
                    .ssbo(7, this.distanceCountBuffer);
            VkBarriers.shaderStorage(cmd);
            this.commandGenShader.bind(cmd);
            this.commandGenShader.dispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            VkBarriers.shaderStorageAndUniform(cmd);

            if (RenderStatistics.enabled) {
                DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                    final int LAYERS = WorldEngine.MAX_LOD_LAYER+1;
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address+i*4L);
                    }
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address+LAYERS*4L+i*4L);
                    }
                });
            }
        }

        VkGpuTimer.mark(cmd, "dc cmdgen");

        {//Translucency sorting
            this.prefixSumShader.ssbo(0, this.distanceCountBuffer);
            VkBarriers.shaderStorage(cmd);
            this.prefixSumShader.bind(cmd);
            this.prefixSumShader.dispatch(cmd, 1, 1, 1);
            VkBarriers.shaderStorage(cmd);

            this.translucentGenShader
                    .ubo(0, this.uniform)
                    .ssbo(1, viewport.drawCallBuffer)
                    .ssbo(2, viewport.drawCountCallBuffer)
                    .ssbo(3, this.geometryManager.getMetadataBuffer())
                    .ssbo(4, viewport.indirectLookupBuffer)
                    .ssbo(5, this.distanceCountBuffer);
            VkBarriers.shaderStorageAndUniform(cmd);
            this.translucentGenShader.bind(cmd);
            this.translucentGenShader.dispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            VkBarriers.shaderStorageAndUniform(cmd);
        }
    }

    public VkViewport createViewport() {
        return new VkViewport(this.properties, this.geometryManager.getMaxSectionCount());
    }

    public void addDebug(List<String> lines) {
    }

    public void free() {
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.translucentTerrainShader.free();
        this.terrainShader.free();
        this.commandGenShader.free();
        this.cullShader.free();
        this.prepShader.free();
        this.translucentGenShader.free();
        this.prefixSumShader.free();
        this.statisticsBuffer.free();
    }

    //Device limit for the indirect draw count
    static final class RenderSystemLimits {
        static int maxDrawIndirectCount() {
            int limit = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().limits().maxDrawIndirectDrawCount();
            return limit <= 0 ? Integer.MAX_VALUE : limit;
        }
    }
}
