package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.FogMode;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.*;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsPipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsState;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.lwjgl.vulkan.VK10.*;

//Vulkan port of AbstractRenderPipeline + NormalRenderPipeline (the iris pipeline is GL only)
public class VkRenderPipeline implements IVkTerrainTargets {
    public final RenderProperties properties;
    private final BooleanSupplier frexStillHasWork;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final VkHierarchicalOcclusionTraverser traversal;
    private final VkSharedIndexBuffers indices;
    private VkSectionRenderer sectionRenderer;

    private final int depthStencilFormat = VkFormats.depthStencil();
    private final VkDepthTarget fb = new VkDepthTarget(this.depthStencilFormat);
    private VkImage2D colourTex;
    private VkImage2D colourSSAOTex;

    private final FogMode fogMode;
    private final VkGraphicsPipeline depthStencilSetup;
    private final VkShaderBuilder finalBlitBuilder;
    private final Map<Long, VkGraphicsPipeline> finalBlits = new HashMap<>();//keyed by the vanilla target formats
    private final VkGpuBuffer blitUniform = new VkGpuBuffer(256).name("FinalBlitUniform");
    private final VkSSAO ssao;

    public static VkShaderBuilder depthStencilSetupShader(RenderProperties properties) {
        return VkShaderBuilder.make()
                .apply(properties::apply)
                .add(ShaderType.VERTEX, "voxy:post/fullscreen2.vert")
                .add(ShaderType.FRAGMENT, "voxy:post/setup_stencil_depth.frag");
    }

    public static VkShaderBuilder finalBlitShader(RenderProperties properties, FogMode fogMode) {
        return VkShaderBuilder.make()
                .apply(properties::apply)
                .add(ShaderType.VERTEX, "voxy:post/fullscreen.vert")
                .add(ShaderType.FRAGMENT, "voxy:post/blit_texture_depth_cutout.frag")
                .defineIf("HAS_FOG", fogMode.hasFog)
                .defineIf("HAS_FADE", fogMode.hasFade)
                .define("EMIT_COLOUR");
    }

    public VkRenderPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, VkHierarchicalOcclusionTraverser traversal, VkSharedIndexBuffers indices, BooleanSupplier frexSupplier) {
        this.properties = properties;
        this.frexStillHasWork = frexSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.indices = indices;

        //If pixel passes, update stencil to 0 and set depth to NEAR (where vanilla terrain exists)
        this.depthStencilSetup = depthStencilSetupShader(properties).compileGraphics(VkGraphicsState.create()
                .depthFormat(this.depthStencilFormat)
                .depthTest(VK_COMPARE_OP_ALWAYS)
                .depthWrite(true)
                .stencil(VK_COMPARE_OP_ALWAYS, 0, 0xFF, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_REPLACE, 0xFF)
                .colorWriteMask(0));

        this.fogMode = VoxyConfig.CONFIG.getFogMode();
        this.finalBlitBuilder = finalBlitShader(properties, this.fogMode);
        this.ssao = VkSSAO.create(properties, VoxyConfig.CONFIG.getSSAOMode());
    }

    public void setSectionRenderer(VkSectionRenderer sectionRenderer) {
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    // ---- IVkTerrainTargets

    @Override
    public int colourFormat() {
        return VK_FORMAT_R8G8B8A8_UNORM;
    }

    @Override
    public int depthFormat() {
        return this.depthStencilFormat;
    }

    @Override
    public void beginOpaque(VkCommandBuffer cmd, VkViewport viewport) {
        var depth = this.fb.getImage();
        VkRendering.begin(cmd, viewport.width, viewport.height, new long[]{this.colourTex.attachmentView(0)}, depth.attachmentView(0), depth.hasStencil);
    }

    @Override
    public void beginTranslucent(VkCommandBuffer cmd, VkViewport viewport) {
        var depth = this.fb.getImage();
        VkRendering.begin(cmd, viewport.width, viewport.height, new long[]{this.colourSSAOTex.attachmentView(0)}, depth.attachmentView(0), depth.hasStencil);
    }

    // ---- frame

    private void setup(VkCommandBuffer cmd, VkViewport viewport, VkFrameTarget target) {
        if (this.colourTex == null || this.colourTex.height != viewport.height || this.colourTex.width != viewport.width) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
            }
            this.fb.resize(viewport.width, viewport.height);
            this.colourTex = new VkImage2D(VK_FORMAT_R8G8B8A8_UNORM, 1, viewport.width, viewport.height).name("VoxyColour");
            this.colourSSAOTex = new VkImage2D(VK_FORMAT_R8G8B8A8_UNORM, 1, viewport.width, viewport.height).name("VoxyColourSSAO");
        }
        this.initDepthStencil(cmd, viewport, target);
    }

    private void initDepthStencil(VkCommandBuffer cmd, VkViewport viewport, VkFrameTarget target) {
        this.fb.clear(this.properties.clearDepth(), 1);
        //Depth copy is done with a fullscreen pass (mismatched depth formats can't be blitted portably)
        var depth = this.fb.getImage();
        VkBarriers.attachmentToSampled(cmd);
        VkRendering.begin(cmd, viewport.width, viewport.height, new long[0], depth.attachmentView(0), depth.hasStencil);
        this.depthStencilSetup.texture(0, target.depthView(), VkSamplers.nearestClamp());
        this.depthStencilSetup.bind(cmd);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var pc = stack.malloc(8);
            pc.putFloat(0, ((float) viewport.width) / target.width());
            pc.putFloat(4, ((float) viewport.height) / target.height());
            this.depthStencilSetup.pushConstants(cmd, pc);
        }
        vkCmdBindIndexBuffer(cmd, this.indices.quad.buffer, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexed(cmd, 6, 1, 0, 0, 0);
        VkRendering.end(cmd);
        VkBarriers.attachmentToSampled(cmd);
    }

    private void innerPrimaryWork(VkCommandBuffer cmd, VkViewport viewport) {
        //Compute the mip chain
        viewport.hiz.buildMipChain(cmd, this.fb.getImage().sampleView, viewport.width, viewport.height);
        VkGpuTimer.mark(cmd, "hiz");

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
            this.nodeCleaner.tick(this.traversal.getNodeBuffer());

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            VkGpuTimer.mark(cmd, "node updates");
            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (this.frexStillHasWork.getAsBoolean());
    }

    public void runPipeline(VkViewport viewport, VkFrameTarget target) {
        var cmd = VkContext.get().stream.cmd();
        VkGpuTimer.beginFrame(cmd);
        this.setup(cmd, viewport, target);
        VkGpuTimer.mark(cmd, "setup");

        var rs = this.sectionRenderer;
        rs.renderOpaque(viewport);
        VkGpuTimer.mark(cmd, "opaque");
        var occlusionDebug = VoxyClient.getOcclusionDebugState();
        if (occlusionDebug==0) {
            this.innerPrimaryWork(cmd, viewport);
        }
        VkGpuTimer.mark(cmd, "traversal");

        if (occlusionDebug<=1) {
            TimingStatistics.G.start();
            rs.buildDrawCalls(viewport);
            TimingStatistics.G.stop();
        }
        VkGpuTimer.mark(cmd, "dc translucent");

        rs.renderTemporal(viewport);
        VkGpuTimer.mark(cmd, "temporal");

        //SSAO, then translucents render into the SSAO output
        this.ssao.computeSSAO(cmd, viewport, this.colourSSAOTex, this.colourTex, this.fb.getImage(), target.depthView());
        VkGpuTimer.mark(cmd, "ssao");

        rs.renderTranslucent(viewport);
        VkGpuTimer.mark(cmd, "translucent");

        this.finish(cmd, viewport, target);
        VkGpuTimer.mark(cmd, "composite");
    }

    private VkGraphicsPipeline finalBlit(VkFrameTarget target) {
        long key = ((long) target.colourFormat() << 32) | (target.depthFormat() & 0xFFFFFFFFL);
        return this.finalBlits.computeIfAbsent(key, k -> this.finalBlitBuilder.clone().compileGraphics(VkGraphicsState.create()
                .colorFormats(target.colourFormat())
                .depthFormat(target.depthFormat())
                .noStencilAttachment()
                .depthTest(this.properties.vkCloserEqualDepthCompare())
                .depthWrite(true)
                .blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)));
    }

    private void finish(VkCommandBuffer cmd, VkViewport viewport, VkFrameTarget target) {
        boolean fogCoversAllRendering = viewport.fogParameters.environmentalEnd()<VoxyRenderSystem.getVanillaRenderDistance();
        //Unbelievably jank hack (same as GL), only blit out to the framebuffer if we are rendering fog
        if (fogCoversAllRendering) {
            return;
        }

        long ptr = UploadStream.INSTANCE.upload(this.blitUniform, 0, 176);
        new Matrix4f(viewport.MVP).invert().getToAddress(ptr);//invProjMat
        new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView).getToAddress(ptr+64);//projMat
        float[] endParams = {0,0,0,0}, fogColour = {0,0,0,0}, fadeParams = {0,0,0,0};
        if (this.fogMode.hasFog) {
            float start = viewport.fogParameters.environmentalStart();
            float end = viewport.fogParameters.environmentalEnd();
            if (Math.abs(end-start)>1) {
                float invEndFogDelta = 1f / (end - start);
                float endDistance = Math.max(VoxyRenderSystem.getVanillaRenderDistance(), 20*16);
                endDistance *= (float)Math.sqrt(3);
                float startDelta = -start * invEndFogDelta;
                endParams = new float[]{invEndFogDelta, startDelta, Math.clamp(endDistance*invEndFogDelta+startDelta, 0, 1), 0};
                fogColour = new float[]{viewport.fogParameters.red(), viewport.fogParameters.green(), viewport.fogParameters.blue(), viewport.fogParameters.alpha()};
            }
        }
        if (this.fogMode.hasFade) {
            int MODE = 1;//0:off, 1:xz, 2:xyz
            float rd = VoxyConfig.CONFIG.sectionRenderDistance*16*32 - (float)Math.sqrt(MODE>1?32*32*32:32*32);
            float vanillaRd = VoxyRenderSystem.getVanillaRenderDistance();
            float start = Math.max(vanillaRd, rd*0.9f);
            float end = Math.max(vanillaRd, rd);
            float scale = 1.0f/(end-start);
            fadeParams = new float[]{MODE, (-start)*scale, scale, 0};
        }
        for (int i = 0; i < 4; i++) {
            MemoryUtil.memPutFloat(ptr+128+i*4, endParams[i]);
            MemoryUtil.memPutFloat(ptr+144+i*4, fogColour[i]);
            MemoryUtil.memPutFloat(ptr+160+i*4, fadeParams[i]);
        }
        UploadStream.INSTANCE.commit();

        var blit = this.finalBlit(target);
        blit.texture(0, this.fb.getImage().sampleView, VkSamplers.nearestClamp())
                .texture(3, this.colourSSAOTex.sampleView, VkSamplers.nearestClamp())
                .ubo(5, this.blitUniform);
        VkBarriers.attachmentToSampled(cmd);
        VkRendering.begin(cmd, target.width(), target.height(), new long[]{target.colourView()}, target.depthView(), false);
        blit.bind(cmd);
        vkCmdBindIndexBuffer(cmd, this.indices.quad.buffer, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexed(cmd, 6, 1, 0, 0, 0);
        VkRendering.end(cmd);
    }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        this.traversal.addDebug(debug);
        RenderStatistics.addDebug(debug);
        this.ssao.addDebugInfo(debug);
    }

    public void free() {
        this.sectionRenderer.free();
        this.depthStencilSetup.free();
        this.finalBlits.values().forEach(VkGraphicsPipeline::free);
        this.blitUniform.free();
        this.ssao.free();
        this.fb.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
    }
}
