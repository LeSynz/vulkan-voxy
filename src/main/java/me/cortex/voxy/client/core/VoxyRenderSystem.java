package me.cortex.voxy.client.core;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.VkModelStore;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.bounding.ColumnStreamedBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.hierachical.VkNodeCleaner;
import me.cortex.voxy.client.core.rendering.section.geometry.VkSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.*;
import me.cortex.voxy.client.core.vulkan.render.*;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderCache;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.List;

//Vulkan render system. Frame flow (ARCHITECTURE_NOTES.md section 1.4): sodium's terrain draw only captures the
// frame inputs (captureFrame), the whole voxy frame is recorded once minecraft's main pass has closed (renderFrame)
// into a command buffer that is spliced into minecraft's submission.
public class VoxyRenderSystem {
    protected final WorldEngine worldIn;
    protected final RenderProperties properties;
    public final StreamedBoundStore visbleSectionStream;

    private final VkContext ctx;
    private final VkSharedIndexBuffers indices;
    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final VkSectionGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final VkHierarchicalOcclusionTraverser traversal;
    private final RenderDistanceTracker renderDistanceTracker;
    private final VkBoundRenderer boundOutlineRenderer;
    private @Nullable ColumnStreamedBoundStore columnStreamedBoundStore;//Only used when FREX is enabled
    private final ViewportSelector<VkViewport> viewportSelector;
    private final VkRenderPipeline pipeline;
    private final VkSectionRenderer sectionRenderer;

    //Frame captured from sodium's terrain pass, rendered by renderFrame
    private @Nullable VkViewport pendingViewport;
    private @Nullable VkFrameTarget pendingTarget;

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        this.worldIn = world;
        this.properties = RenderProperties.getRenderProperties();
        this.visbleSectionStream = new StreamedBoundStore();
        Logger.info("Creating Voxy vulkan render system");
        final long createStart = System.nanoTime();
        long lap = createStart;
        final var laps = new StringBuilder();
        try {
            this.ctx = VkContext.get();
            if (!this.ctx.voxyFeaturesEnabled) {
                throw new IllegalStateException("Vulkan device does not have the features voxy needs enabled");
            }

            if (Minecraft.getInstance().options.renderDistance().get()<3) {
                String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
                Logger.warn(msg);
                Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal(msg), false);
            }

            this.indices = new VkSharedIndexBuffers();
            laps.append(String.format(" %s=%.1f", "indices", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            this.modelService = new ModelBakerySubsystem(world.getMapper());
            laps.append(String.format(" %s=%.1f", "modelService", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            this.renderGen = new RenderGenerationService(world, this.modelService, sm, false);
            laps.append(String.format(" %s=%.1f", "renderGen", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            this.geometryData = new VkSectionGeometryData(1<<20);
            laps.append(String.format(" %s=%.1f", "geometryData", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
            laps.append(String.format(" %s=%.1f", "nodeManager", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            this.nodeCleaner = new VkNodeCleaner(this.nodeManager);
            this.traversal = new VkHierarchicalOcclusionTraverser(this.properties, this.nodeManager, this.nodeCleaner, this.renderGen);
            laps.append(String.format(" %s=%.1f", "traversal", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();

            world.setDirtyCallback(this.nodeManager::worldEvent);
            Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
            world.getMapper().setBiomeCallback(this.modelService::addBiome);
            this.nodeManager.start();

            this.pipeline = new VkRenderPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this.indices, this::frexStillHasWork);
            laps.append(String.format(" %s=%.1f", "pipeline", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            this.sectionRenderer = new VkSectionRenderer(this.properties, this.pipeline, (VkModelStore) this.modelService.getStore(), this.geometryData, this.indices);
            laps.append(String.format(" %s=%.1f", "sectionRenderer", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            this.pipeline.setSectionRenderer(this.sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(this.sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;
                if (VoxyCommon.IS_MINE_IN_ABYSS) {
                    minSec = -8;
                    maxSec = 7;
                }
                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);
                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.boundOutlineRenderer = new VkBoundRenderer(this.properties, this.indices);
            laps.append(String.format(" %s=%.1f", "boundRenderer", (System.nanoTime()-lap)/1e6)); lap = System.nanoTime();
            //Initial uploads/clears, renderer creation never happens inside a minecraft render pass
            UploadStream.INSTANCE.commit();
            this.ctx.stream.flush();

            VkShaderCache.savePipelineCache();
            Logger.info("Voxy vulkan render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity in "
                    + String.format("%.1f", (System.nanoTime() - createStart) / 1e6) + " ms (" + VkShaderCache.summary() + ") steps(ms):" + laps);
        } catch (RuntimeException e) {
            world.releaseRef();
            throw e;
        }
    }

    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, FogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        float farPlaneChunks = 3000;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection, farPlaneChunks*16);
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }
        return viewport;
    }

    //Called from sodium's terrain pass (inside minecraft's render pass): only remember what to render
    public void captureFrame(Viewport<?> viewport, GpuTextureView colour, GpuTextureView depth) {
        if (viewport == null) {
            return;
        }
        this.pendingViewport = (VkViewport) viewport;
        this.pendingTarget = VkFrameTarget.of(colour, depth);
    }

    //Called once minecraft's main pass has been closed
    public void renderFrame() {
        var viewport = this.pendingViewport;
        var target = this.pendingTarget;
        this.pendingViewport = null;
        this.pendingTarget = null;
        if (viewport == null || target == null) {
            return;
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;
        }

        TimingStatistics.resetSamplers();
        TimingStatistics.all.start();
        TimingStatistics.main.start();

        TimingStatistics.E.start();
        if (this.visbleSectionStream != null && (!VoxyClient.disableSodiumChunkRender())) {
            if (VoxyClient.isFrexActive()!=(this.columnStreamedBoundStore!=null)) {
                if (this.columnStreamedBoundStore == null) {
                    this.columnStreamedBoundStore = new ColumnStreamedBoundStore();
                } else {
                    this.columnStreamedBoundStore.free();
                    this.columnStreamedBoundStore = null;
                }
            }
            this.boundOutlineRenderer.render(viewport, this.columnStreamedBoundStore==null?this.visbleSectionStream:this.columnStreamedBoundStore);
        } else {
            viewport.depthBounding.clear(this.properties.inverseClearDepth());
        }
        TimingStatistics.E.stop();

        this.pipeline.runPipeline(viewport, target);

        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();
        {
            UploadStream.INSTANCE.tick();
            //FREX (flawless frames) loops are not supported on vulkan: completing gpu work inside a frame would
            // require minecraft to submit, so process a single step like a normal frame
            this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ);
            TimingStatistics.H.start();
            this.modelService.tick(900_000);
            TimingStatistics.H.stop();
        }
        TimingStatistics.postDynamic.stop();
        TimingStatistics.all.stop();

        this.ctx.stream.flush();
    }

    private boolean frexStillHasWork() {
        return false;//See renderFrame
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance+1));
    }

    public Viewport<?> getViewport() {
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Vulkan Buf/Tex [#/Mb]: [" + VkGpuBuffer.getCount() + "/" + (VkGpuBuffer.getTotalSize()/1_000_000) + "],[" + VkImage2D.getCount() + "/" + (VkImage2D.getEstimatedTotalSize()/1_000_000)+"]");
        this.modelService.addDebugData(debug);
        this.renderGen.addDebugData(debug);
        this.nodeManager.addDebug(debug);
        this.pipeline.addDebug(debug);
        debug.addAll(VkGpuTimer.debugLines());
        TimingStatistics.update();
        debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down vulkan rendering");
        try {
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();
            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            this.boundOutlineRenderer.free();
            if (this.visbleSectionStream != null) {
                this.visbleSectionStream.free();
            }
            if (this.columnStreamedBoundStore != null) {
                this.columnStreamedBoundStore.free();
                this.columnStreamedBoundStore = null;
            }
            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}
        try {this.indices.free();} catch (Exception e){Logger.error("Error releasing index buffers", e);}

        DownloadStream.INSTANCE.flushWaitClear();
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }

    public static float getVanillaRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    protected static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base, float farPlane) {

        //this jank is to capture the extra crap they inject like viewbobbing
        var rawMCProj = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        float near = getVanillaRenderDistance()<=32.0f?8f:16f;
        near = VoxyClient.disableSodiumChunkRender()?0.1f:near;

        float far = farPlane;

        //Flip near and far on reverse depth
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }
}
