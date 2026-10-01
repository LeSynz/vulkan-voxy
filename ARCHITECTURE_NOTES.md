# Voxy → Vulkan: architecture notes

Target: Minecraft 26.3 (Fabric), Sodium mc26.3-0.9.2, Iris 1.11.6+26.3. Voxy 0.2.20-beta.

This file lists every OpenGL usage in Voxy (by file) and the planned Vulkan equivalent, plus
the facts about Minecraft's own Vulkan backend that the plan depends on. Nothing here has been
run on a GPU; everything Vulkan-side is "needs visual check".

---------------------------------------------------------------------------------------------------

## 1. What Minecraft 26.3 gives us ("renderpearl")

Minecraft 26.3 replaced Blaze3D's GL-only device with `com.mojang.renderpearl`:

* `com.mojang.renderpearl.api.*` – backend-agnostic API (`GpuDevice`, `CommandEncoder`,
  `RenderPass`, `GpuBuffer`, `GpuTexture(View)`, `GpuSampler`, `GpuFence`, `DeviceInfo`).
  `RenderSystem.getDevice()` returns a `frontend.FrontendGpuDevice`.
* `backend.opengl.*` – GL backend (`GlDevice`, `GlTexture.glId()`, `GlStateManager`, ...).
* `backend.vulkan.*` – Vulkan backend (LWJGL Vulkan + VMA + shaderc, all shipped by Minecraft:
  `lwjgl-vulkan`, `lwjgl-vma`, `lwjgl-shaderc`, `lwjgl-spvc` 3.4.3).

The active API is visible through `DeviceInfo.backendName()` and, more reliably, through
`FrontendGpuDevice.backend` being a `VulkanDevice` (private field → accessor mixin).

### 1.1 Things the Vulkan backend exposes that we can use directly

| Need | Minecraft API (decompiled 26.3) |
|---|---|
| `VkDevice`, `VkPhysicalDevice`, `VkInstance` | `VulkanDevice.vkDevice()`, `.instance()` |
| Memory allocator | `VulkanDevice.vma()` (a VMA allocator handle) |
| Queue | `VulkanDevice.graphicsQueue()` (`VulkanQueue(vkQueue, familyIndex)`) |
| Command buffers in Minecraft's own stream | `VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()` + `VulkanCommandEncoder.execute(VkCommandBuffer)` – `execute` ends Minecraft's current command buffer and appends ours to the same `vkQueueSubmit2`, so ordering with vanilla work is exact. Must not be called while a vanilla `RenderPass` is open (throws). |
| Completion tracking | `CommandEncoder.createFence()` → `GpuFence` backed by Minecraft's timeline semaphore (`awaitCompletion(0)` polls). |
| Deferred destruction | `VulkanCommandEncoder.queueForDestroy(Destroyable)` (destroys after `MAX_SUBMITS_IN_FLIGHT` = 2 submits). |
| Image handles of vanilla targets | `VulkanGpuTextureView.vkImageView()`, `.texture().vkImage()` |
| Lightmap / block atlas | same views; the atlas can also be read back with the backend-agnostic `CommandEncoder.copyTextureToBuffer` |

### 1.2 Conventions of Minecraft's Vulkan backend that the port must follow

* **Every image lives in `VK_IMAGE_LAYOUT_GENERAL`.** Textures are transitioned UNDEFINED→GENERAL
  at creation and render passes use `imageLayout = GENERAL`. We do the same for every image we
  create, so there are no layout transitions to track – only memory/execution barriers.
* **Synchronisation is a full barrier.** After every render pass / copy / clear Minecraft records
  `vkCmdPipelineBarrier2(ALL_COMMANDS, MEMORY_READ|MEMORY_WRITE → ALL_COMMANDS, MEMORY_READ|MEMORY_WRITE)`.
  Our command buffers start and end with the same barrier, so vanilla → Voxy → vanilla hand-offs
  are always safe. Inside our command buffers we use narrower barriers.
* **Dynamic rendering** (`VK_KHR_dynamic_rendering`), **synchronization2**, **push descriptors**
  (`VK_KHR_push_descriptor`) and **timeline semaphores** are required by Minecraft, so we can rely
  on them.
* **Y axis.** Minecraft's shaders write `gl_Position = ProjMat * ...` unchanged, viewport height
  is positive, `frontFace = CLOCKWISE`, and the surface blit flips the image at present time.
  Consequence: NDC y = -1 lands in texel row 0 in both GL and Vulkan, so Voxy's projection
  matrices, `gl_FragCoord`, UV and `texelFetch` maths carry over unchanged; only the winding of
  culled draws must be inverted (GL CCW == Vulkan CW here).
* **Depth range.** Minecraft's Vulkan device reports `isZZeroToOne() == true`; Voxy already has
  the `USE_ZERO_ONE_DEPTH` path (`RenderProperties.isZero2One`). Reverse-Z is detected the same
  way as today (`DepthStencilState.DEFAULT`).
* **SPIR-V target.** Minecraft compiles with shaderc for Vulkan 1.2 (`target_env vulkan`,
  `0x402000`). We use the same.

### 1.3 Features Voxy needs that Minecraft does NOT enable by default

Minecraft's required feature set: `multiDrawIndirect`, `drawIndirectFirstInstance`,
`samplerAnisotropy`, `shaderDrawParameters`, `timelineSemaphore`, `hostQueryReset`,
`synchronization2`, `dynamicRendering`, `vertexAttributeInstanceRateDivisor` (+ optional
`multiDraw`, `fillModeNonSolid`, calibrated timestamps, checkpoints).

Voxy additionally needs:

| Vulkan feature | Why |
|---|---|
| `VkPhysicalDeviceVulkan12Features.drawIndirectCount` | `glMultiDrawElementsIndirectCountARB` (MDIC terrain draw) |
| `VkPhysicalDeviceFeatures.fragmentStoresAndAtomics` | occlusion-cull raster writes visibility from the fragment shader |
| `VkPhysicalDeviceFeatures.vertexPipelineStoresAndAtomics` | `writeonly`/`readonly` SSBO declarations reached from vertex stages |
| `VkPhysicalDeviceFeatures.shaderInt64` | `uint64_t` quad format (`lod/quad_format.glsl`) |

Plan: a mixin on `VulkanFeatureSets.optionalFeatureSets()` adds a `"Voxy"` optional `FeatureSet`
with those features. Minecraft then enables them itself when the device supports them (optional
sets are skipped otherwise, so vanilla never fails to start because of Voxy). At render-system
creation we check that the set was enabled (accessor on `VulkanDevice.enabledFeatures`) and
refuse to start the Vulkan renderer with a clear log message otherwise.

Not available on Minecraft's device (no extension enabled, and we can't add extensions that
Minecraft's code doesn't know about without risk): `VK_EXT_index_type_uint8` (Voxy uses byte
index buffers), sparse buffers, `NV_representative_fragment_test`, `NVX_gpu_memory_info`.

### 1.4 Where Voxy runs in the frame on Vulkan

On GL, Voxy renders from `MixinDefaultChunkRenderer.doRender` – i.e. *inside* Sodium's draw of the
`CUTOUT` pass, inside the vanilla "Solid"/"Main" render pass. On Vulkan that is impossible: the
vanilla `RenderPass` is a live `vkCmdBeginRendering` scope, we can't dispatch compute in it and
`VulkanCommandEncoder.execute` throws while it is open.

Plan for Vulkan:
1. `MixinDefaultChunkRenderer.doRender` (CUTOUT pass) only *captures* the frame inputs
   (matrices, camera, fog, vanilla colour/depth target views) and calls `setupViewport` (CPU
   only).
2. A mixin on `LevelRenderer` fires right after the "Solid"/"Main" pass closes (HEAD of
   `executeOit` when improved transparency is on, else HEAD of `executeOutline`; guarded so it
   runs once per frame). There Voxy records its whole frame into one command buffer and
   splices it in with `VulkanCommandEncoder.execute`.
3. Known visual difference: with *classic* transparency, vanilla translucent terrain is drawn in
   the same Main pass, i.e. before Voxy, so far LODs are composited behind already-drawn water.
   With improved transparency (OIT, separate pass) the order matches GL. Needs visual check.

---------------------------------------------------------------------------------------------------

## 2. Backend abstraction

* `client/core/backend/GpuBackendType` {OPENGL, VULKAN} + `GpuBackend.current()` – resolved from
  `RenderSystem.getDevice()` once at init.
* `client/core/backend/IVoxyRenderer` – what the mixins talk to (`setupViewport`, `renderOpaque`
  (GL) / `captureFrame`+`renderFrame` (VK), `setRenderDistance`, `getViewport`, `addDebugInfo`,
  `shutdown`, `getEngine`). The current `VoxyRenderSystem` becomes the GL implementation without
  behaviour changes; `client/core/vulkan/VkVoxyRenderSystem` is the Vulkan one.
* CPU-side subsystems are shared unchanged: world engine, `RenderGenerationService` (meshing),
  `NodeManager` (CPU node tree), model baking (`ModelFactory` CPU part, `SoftwareRasterizer`),
  `RenderDistanceTracker`, bound stores' CPU lists.
* GPU-facing seams that both backends implement:
  * buffers: `IGpuBuffer` (size, free) – `GlBuffer` and `VkGpuBuffer` implement it.
  * upload: `UploadStream` API (`upload(buffer, offset, size) → host pointer`, `commit`, `tick`)
    gets a Vulkan twin `VkUploadStream` (persistently mapped host-visible VMA buffer +
    `vkCmdCopyBuffer`, frames retired with Minecraft's `GpuFence`).
  * download: `DownloadStream` twin `VkDownloadStream` (`vkCmdCopyBuffer` into host-cached mapped
    memory, callbacks on fence completion).
  * The GPU side of `AsyncNodeManager.tick`, `NodeCleaner`, geometry data, model store are moved
    behind small backend interfaces so the CPU logic is shared.

The GL code path is kept byte-for-byte equivalent in behaviour (only types widened to the shared
interfaces where needed).

---------------------------------------------------------------------------------------------------

## 3. Vulkan building blocks (`client/core/vulkan/`)

| Class | Replaces (GL) | Vulkan implementation |
|---|---|---|
| `VkContext` | `GL.getCapabilities()`, `Capabilities` | Wraps Minecraft's `VulkanDevice` (device, VMA, queue, encoder), physical-device limits/properties (`minStorageBufferOffsetAlignment`, `maxStorageBufferRange`, subgroup properties, memory heaps via `VK_EXT_memory_budget` if present – otherwise heap size), vendor detection. |
| `VkCommandStream` | implicit GL command stream | Lazily `allocateAndBeginTransientCommandBuffer()`; `flush()` = full barrier, `vkEndCommandBuffer`, `encoder.execute(cb)`. Never kept across a Minecraft submit. |
| `VkGpuBuffer` | `GlBuffer` (`glNamedBufferStorage`, `glClearNamedBuffer(Sub)Data`) | `vmaCreateBuffer`, usage STORAGE/UNIFORM/INDEX/INDIRECT/TRANSFER_SRC/DST, device-local; `zero/fill/zeroRange` → `vkCmdFillBuffer`; destruction deferred via `queueForDestroy`. |
| `VkMappedBuffer` | `GlPersistentMappedBuffer` | VMA `HOST_ACCESS_SEQUENTIAL_WRITE` (upload) / `HOST_ACCESS_RANDOM` (download) + `MAPPED` flag; explicit `vmaFlushAllocation` / `vmaInvalidateAllocation` when not coherent. |
| `VkImage2D` | `GlTexture` (`glTextureStorage2D`, `glTextureView`, `glClearTexImage`) | `vmaCreateImage`, transitioned to GENERAL once; per-mip `VkImageView`s; `vkCmdClearColorImage` / `vkCmdClearDepthStencilImage`. |
| `VkSamplerCache` | `glGenSamplers` + `glSamplerParameteri` | `vkCreateSampler`, cached by parameters. |
| `VkShaderCompiler` | `Shader.Builder.compileToProgram` | Same `#import` + `#define` + replacement processing as `ShaderLoader`, then shaderc → SPIR-V (Vulkan 1.2). Adds `VOXY_VULKAN` define. Compile errors dump the source like `SHADER_DUMP.txt`. |
| `VkComputePipeline`, `VkGraphicsPipeline` | `glUseProgram` + all GL raster state | One push-descriptor set layout per pipeline (binding numbers from the shader's `layout(binding=N)`), push-constant range replaces `layout(location=N) uniform`. Graphics pipelines use dynamic rendering (`VkPipelineRenderingCreateInfo`), dynamic viewport/scissor; depth/stencil/blend/colour-mask/cull/polygon mode baked into the pipeline (one pipeline per GL state combination Voxy actually uses). |
| `VkDescriptorWriter` | `glBindBufferBase/Range`, `glBindTextureUnit`, `glBindSampler`, `glBindImageTexture` | `vkCmdPushDescriptorSetKHR`. |
| `VkBarriers` | `glMemoryBarrier(...)`, `glTextureBarrier` | `vkCmdPipelineBarrier2` with matching stage/access masks (table in §5). |
| `VkTimestampQueries` | `GPUTiming` (`glQueryCounter`) | `vkCmdWriteTimestamp2` into a query pool, read back on fence. |

---------------------------------------------------------------------------------------------------

## 4. Every GL usage, by file, and its Vulkan equivalent

Paths relative to `src/main/java/me/cortex/voxy/client/`.

### 4.1 GL wrapper layer (`core/gl/`)

| File | GL features used | Vulkan equivalent |
|---|---|---|
| `core/gl/Capabilities.java` | `GL.getCapabilities`, extension checks (`ARB_sparse_buffer`, `NV_representative_fragment_test`, `NV_mesh_shader`, `NVX_gpu_memory_info`, `KHR_shader_subgroup`, `NV_fragment_shader_barycentric`), shader-compile probes, `GL_MAX_SHADER_STORAGE_BLOCK_SIZE`, `GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT`, `glGetString(VENDOR/VERSION)`, AMD depth-sampler test (compute + `glClearNamedFramebufferfi` + map) | `VkContext`: `VkPhysicalDeviceProperties(2)` (vendorID, limits `maxStorageBufferRange`, `minStorageBufferOffsetAlignment`), `VkPhysicalDeviceSubgroupProperties` (`supportedOperations`, `supportedStages`), `VkPhysicalDeviceMemoryProperties` (+`VK_EXT_memory_budget` if enabled) for memory. Sparse/rep-frag/mesh/NVX/barycentric = false. AMD depth-sampler GL driver bug test not applicable. |
| `core/gl/GlBuffer.java` | `glCreateBuffers`, `nglNamedBufferStorage`, `glClearNamedBufferData/SubData` (R8UI/R32UI), `glDeleteBuffers`, `glPixelStorei` (mesa workaround) | `VkGpuBuffer` (VMA device-local), `vkCmdFillBuffer` |
| `core/gl/GlPersistentMappedBuffer.java` | `glNamedBufferStorage(MAP_PERSISTENT/COHERENT/WRITE/READ/CLIENT_STORAGE)`, `nglMapNamedBufferRange`, `glUnmapNamedBuffer` | `VkMappedBuffer` (VMA mapped allocation) |
| `core/gl/GlTexture.java` | `glCreateTextures`, `glTextureStorage2D`, `glTextureView`, `nglClearTexImage`, `glDeleteTextures` | `VkImage2D` + `VkImageView` per level/view, `vkCmdClear*Image` |
| `core/gl/GlFramebuffer.java` | `glCreateFramebuffers`, `glNamedFramebufferTexture/Renderbuffer`, `glNamedFramebufferDrawBuffers`, `glCheckNamedFramebufferStatus` | No object: `VkRenderingInfo` attachments chosen per pass (dynamic rendering). |
| `core/gl/GlRenderBuffer.java` | `glCreateRenderbuffers`, `glNamedRenderbufferStorage` | Not used by live code; `VkImage2D` if ever needed. |
| `core/gl/GlFence.java` | `glFenceSync`, `nglGetSynciv`, `glDeleteSync` | Minecraft `GpuFence` (timeline semaphore) via `CommandEncoder.createFence()`. |
| `core/gl/GlVertexArray.java` | `glCreateVertexArrays`, attrib formats, `STATIC_VAO` | Not needed: all Voxy draws are vertex-pulling (no vertex input state). |
| `core/gl/GlDebug.java` | `glObjectLabel` | `VK_EXT_debug_utils` names only if Minecraft enabled debug utils (optional, skip otherwise). |
| `core/gl/shader/Shader.java` | `glCreateShader/ShaderSource/CompileShader/CreateProgram/LinkProgram/UseProgram/DeleteProgram`, logs | `VkShaderCompiler` + pipelines |
| `core/gl/shader/AutoBindingShader.java` | `glBindBufferBase/Range` (SSBO/UBO), `glBindTextureUnit`, `glBindSampler` | push descriptors recorded at bind time |
| `core/gl/shader/PrintfInjector.java` | `glBindBufferBase`, `nglClearNamedBuffer(Sub)Data` for the printf buffer | Same buffer as `VkGpuBuffer`; debug-only, ported last. |
| `core/gl/shader/ShaderType.java` | `GL_VERTEX/FRAGMENT/COMPUTE_SHADER`, `GL_MESH/TASK_SHADER_NV` | shaderc kinds (mesh/task unused). |

### 4.2 Render system / pipelines

| File | GL features used | Vulkan equivalent |
|---|---|---|
| `core/VoxyRenderSystem.java` | Save/restore of SSBO bindings (`glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING)`), `glFinish`, `GlStateManager` resets (depth test/func/mask, blend, VAO, program, texture units, samplers), `glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)`, `glGetIntegerv(GL_VIEWPORT)`, `glGetTextureLevelParameteri(GL_TEXTURE_WIDTH/HEIGHT)`, `glViewport`, `glBindFramebuffer` | `VkVoxyRenderSystem`: no global state to save/restore (Vulkan has none; our CB is bracketed by full barriers). Source size from `GpuTextureView.getWidth/Height(0)`. `glFinish` → `VulkanQueue.waitIdle()` only at create/shutdown. |
| `core/AbstractRenderPipeline.java` | depth sampler (`glGenSamplers`), `glClearNamedFramebufferfi`, stencil state (`glStencilOp/Func/Mask`), `glDepthFunc/Mask`, `glColorMask`, `glBindTextureUnit/Sampler`, `glUniform2f`, `nglUniformMatrix4fv`, fullscreen blit, `glMemoryBarrier(FRAMEBUFFER|PIXEL_BUFFER)` | `vkCmdClearDepthStencilImage`; "setup stencil depth" = graphics pipeline (depth ALWAYS, stencil REPLACE, no colour writes) inside a dynamic-rendering pass on Voxy's D24S8/D32S8 image; uniforms → push constants; barrier table §5. |
| `core/NormalRenderPipeline.java` | `GlTexture` RGBA8 colour targets, `GlFramebuffer`, `glTextureParameterf` (filters), `GL_DEPTH_STENCIL_TEXTURE_MODE`, `glUniform4f` (fog/fade), `glBlendFuncSeparate`, `glEnable/Disable(BLEND/STENCIL/DEPTH)`, `glBindTextureUnit` | `VkImage2D` targets, samplers, final composite = graphics pipeline with blend `SRC_ALPHA, ONE_MINUS_SRC_ALPHA / ONE, ONE_MINUS_SRC_ALPHA` writing colour+depth into the vanilla main target (dynamic rendering on vanilla's image views, layout GENERAL). Depth-only view of the D/S image for sampling (`VK_IMAGE_ASPECT_DEPTH_BIT` view). |
| `core/IrisVoxyRenderPipeline.java` | Iris framebuffers, `glBlitNamedFramebuffer`, `glNamedFramebufferDrawBuffers`, `glTextureBarrier`, `glClear*`, `glBindBufferBase` | **Blocked on Vulkan**: Iris 1.11.6 is an OpenGL renderer; there is nothing to integrate with on the Vulkan backend. Vulkan path always uses the normal pipeline. GL path unchanged. |
| `core/SSAO.java` | compute shader, `glBindImageTexture(RGBA8, READ_WRITE)`, samplers, `nglUniformMatrix4fv` | compute pipeline, storage image (RGBA8 supports storage on all desktop GPUs; check `VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT`), combined image samplers, push constants (2–4 mat4 = 256 B ≤ 128 B guaranteed min? **No**: 4 mat4 = 256 B > 128 B min `maxPushConstantsSize`; use a small UBO from the upload stream instead). |
| `core/RenderResourceReuse.java` | `glGetError` (OOM detection), sparse buffer fallback | VMA result codes; no sparse fallback (documented). |
| `core/RenderProperties.java` | `GL_GEQUAL/LEQUAL/...` enums | `VK_COMPARE_OP_*` mapping helper. |

### 4.3 Rendering subsystems

| File | GL features used | Vulkan equivalent |
|---|---|---|
| `core/rendering/util/UploadStream.java` | persistent mapped buffer, `glFlushMappedNamedBufferRange`, `glCopyNamedBufferSubData`, `glMemoryBarrier(BUFFER_UPDATE)`, `GlFence`, `glFinish` | `VkUploadStream`: VMA mapped buffer, `vmaFlushAllocation`, batched `vkCmdCopyBuffer` (one call per target buffer with many `VkBufferCopy` regions), TRANSFER→ALL barrier, retirement via `GpuFence`. |
| `core/rendering/util/DownloadStream.java` | `glCopyNamedBufferSubData`, `glMemoryBarrier(CLIENT_MAPPED_BUFFER)`, `GlFence`, `glFinish` | `VkDownloadStream`: `vkCmdCopyBuffer` into mapped buffer, `vmaInvalidateAllocation` before callbacks. |
| `core/rendering/util/RawDownloadStream.java` | persistent mapped buffer + fences | Same as above (only used by debug code). |
| `core/rendering/util/SharedIndexBuffer.java` | `GlBuffer` index data (u16 quads, **u8** cube/quad indices) | `VkGpuBuffer` INDEX usage; u8 index buffers re-generated as **u16** on Vulkan (no `indexTypeUint8`). |
| `core/rendering/util/DepthFramebuffer.java` | D24 / D24S8 texture + FBO, `nglClearNamedFramebufferfv/iv` | `VkImage2D` D24S8 (fallback D32_SFLOAT_S8 if `D24_UNORM_S8` lacks `DEPTH_STENCIL_ATTACHMENT`; AMD lacks it) / D32, `vkCmdClearDepthStencilImage`. |
| `core/rendering/util/HiZBuffer.java` | depth mip chain via fragment shader + `glTextureBarrier`, per-level `GL_TEXTURE_BASE/MAX_LEVEL`, samplers | Per-level depth attachment views; one dynamic-rendering pass per mip writing `gl_FragDepth` from the previous mip (sampled through a single-level view, so no feedback loop), barrier between levels (§5). |
| `core/rendering/util/HiZBuffer2.java` | compute HiZ with `glBindImageTexture(R32F)` | Alternative path (unused by default) – compute pipeline with storage images; ported after HiZBuffer. |
| `core/rendering/util/LightMapHelper.java` | `GlTexture.glId()` of vanilla lightmap, sampler | `VulkanGpuTextureView` of `gameRenderer.levelLightmap()` + linear clamp sampler. |
| `core/rendering/util/PrintfDebugUtil.java` | printf buffer download | Debug-only; port after core. |
| `core/rendering/util/BufferArena.java` | `GlBuffer` + upload/download | Generic over `IGpuBuffer`. |
| `core/rendering/post/FullscreenBlit.java` | empty VAO, `glDrawElements(u8 index)` | `vkCmdDraw(3)` / `vkCmdDrawIndexed` with u16 index buffer; vertex shader uses `gl_VertexIndex`. |
| `core/rendering/section/geometry/BasicSectionGeometryData.java` | geometry `GlBuffer` (up to 4 GiB), sparse commitment (`glBufferPageCommitmentARB`), `glFinish`, `glGetError` | `VkGpuBuffer` sized against `maxStorageBufferRange` (often 4 GiB-1 or 2^27 on some drivers! clamp and use multiple ranges if needed) and VMA budget; no sparse. |
| `core/rendering/section/geometry/BasicSectionGeometryManager.java` | upload stream | shared via `IGpuBuffer`. |
| `core/rendering/section/backend/mdic/MDICSectionRenderer.java` | `glMultiDrawElementsIndirectCountARB` (u16), `glDrawElementsIndirect` (u8 cube), `glDispatchCompute(Indirect)`, `glMemoryBarrier(COMMAND|SHADER_STORAGE|UNIFORM)`, `glProvokingVertex(FIRST)`, `glPolygonMode(LINE)` (debug), `GL_REPRESENTATIVE_FRAGMENT_TEST_NV`, depth/colour masks, blending, UBO/SSBO/texture bindings | `vkCmdDrawIndexedIndirectCount` (u16, stride 20), `vkCmdDrawIndexedIndirect` (u16 cube), `vkCmdDispatch(Indirect)`; provoking vertex: Vulkan default is first vertex (= GL_FIRST_VERTEX_CONVENTION); wireframe pipeline only if `fillModeNonSolid` enabled; rep-frag-test skipped. |
| `core/rendering/section/backend/mdic/MDICViewport.java` | `GlBuffer`s | `VkGpuBuffer`s (INDIRECT\|STORAGE usage). |
| `core/rendering/hierachical/HierarchicalOcclusionTraverser.java` | compute traversal loop (`glDispatchCompute`, `glDispatchComputeIndirect`, `glUniform1ui`), `glClearNamedBufferSubData` single-int writes, HiZ sampler, `glPixelStorei` mesa fix | compute pipeline + push constant for the iteration index; single-int writes → `vkCmdUpdateBuffer` / `vkCmdFillBuffer`; indirect dispatch from the queue-meta buffer. |
| `core/rendering/hierachical/AsyncNodeManager.java` | scatter-write + multi-memcpy compute with `glBindBufferRange` into the upload buffer, `glUniform1ui` | Same compute shaders; descriptors point at the upload stream's `VkBuffer` + offset (offset aligned to `minStorageBufferOffsetAlignment`); count via push constant. |
| `core/rendering/hierachical/NodeCleaner.java` | sort/transform/batch-clear compute, `glCopyNamedBufferSubData` (self copy), `glGetNamedBufferSubData` (debug) | compute pipelines; self copy → `vkCmdCopyBuffer` (non-overlapping 4-byte regions); debug readbacks via download stream. |
| `core/rendering/hierachical/NodeManager.java` | node writes through `UploadStream` | via shared upload API. |
| `core/rendering/hierachical/DebugRenderer.java` | compute + `glDrawElementsIndirect(u8)` | debug only; pipeline + u16 index. |
| `core/rendering/bounding/BoundRenderer.java` | `glDrawElementsInstanced(BaseInstance)` (u8 indices), `glFrontFace(GL_CW)`, cull, depth func | graphics pipeline: cull BACK with `frontFace = COUNTER_CLOCKWISE` (inverse of the GL_CW-under-flip equivalence), `vkCmdDrawIndexed(firstInstance)` with u16 cube indices. |
| `core/rendering/bounding/*BoundStore.java` | `GlBuffer`, `glCopyNamedBufferSubData` | `IGpuBuffer`, `vkCmdCopyBuffer`. |
| `core/util/GPUTiming.java` | `glQueryCounter(GL_TIMESTAMP)`, query buffer objects | `vkCmdWriteTimestamp2` + `VkQueryPool` (hostQueryReset is enabled by Minecraft). |

### 4.4 Models

| File | GL features used | Vulkan equivalent |
|---|---|---|
| `core/model/ModelStore.java` | model/colour `GlBuffer`s, atlas `GlTexture` (RGBA8, mipped), sampler (`NEAREST_MIPMAP_LINEAR`, max LOD), bind helpers | `VkGpuBuffer`s, `VkImage2D` (RGBA8, mips), sampler cache. |
| `core/model/ModelFactory.java` | `nglTextureSubImage2D` per mip, `glPixelStorei` | staging via `VkUploadStream` + `vkCmdCopyBufferToImage` per mip. |
| `core/model/bakery/SoftwareModelTextureBakery.java` | `glGetTextureImage` of the vanilla block atlas (`GlTexture.glId()`), `glFinish` | Backend-agnostic `CommandEncoder.copyTextureToBuffer` into a `GpuBuffer` (MAP_READ) + fence wait (one-off at bake setup). |
| `core/model/bakery/BakedBlockEntityModel.java` | `GlTexture.glId()` for block-entity textures | File is entirely commented out in Voxy (not compiled) — nothing to port. |

### 4.5 Mixins / integrations

| File | GL usage | Vulkan plan |
|---|---|---|
| `mixin/sodium/MixinDefaultChunkRenderer.java` | `GlTextureView.glId()` of Sodium's target | Branch on backend; Vulkan: capture `VulkanGpuTextureView`s, defer to the LevelRenderer hook (§1.4). |
| `mixin/nvidium/MixinRenderPipeline.java` | `GlTextureView.glId()` | Nvidium is GL-only (NV mesh shaders) – GL path only. |
| `mixin/minecraft/util/MixinGlDebug.java` | wraps GL debug log | GL only; Vulkan validation messages come through Minecraft's own debug messenger. |
| `mixin/iris/*`, `client/iris/*` (`IrisShaderPatch`, `IrisVoxyRenderPipelineData`, `MixinLevelRenderer` `glViewport`) | Iris GL integration | GL only (Iris has no Vulkan pipeline). |
| `mixin/minecraft/MixinRenderSystem.java` | init hook (`initRenderer(GpuDevice)`) | Selects backend here. |

---------------------------------------------------------------------------------------------------

## 5. Barrier translation table

| GL | Vulkan (sync2) |
|---|---|
| `GL_SHADER_STORAGE_BARRIER_BIT` | COMPUTE\|VERTEX\|FRAGMENT `SHADER_STORAGE_WRITE` → COMPUTE\|VERTEX\|FRAGMENT `SHADER_STORAGE_READ\|WRITE` |
| `GL_COMMAND_BARRIER_BIT` | COMPUTE `SHADER_STORAGE_WRITE` → `DRAW_INDIRECT` `INDIRECT_COMMAND_READ` |
| `GL_UNIFORM_BARRIER_BIT` | writer → `UNIFORM_READ` |
| `GL_BUFFER_UPDATE_BARRIER_BIT` | `COPY` `TRANSFER_WRITE` ↔ shader stages |
| `GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT` | `COPY` `TRANSFER_WRITE` → `HOST` `HOST_READ` |
| `GL_FRAMEBUFFER_BARRIER_BIT`, `glTextureBarrier` | `LATE_FRAGMENT_TESTS\|COLOR_ATTACHMENT_OUTPUT` attachment writes → `FRAGMENT\|COMPUTE` `SHADER_SAMPLED_READ` |
| `GL_TEXTURE_FETCH_BARRIER_BIT` | writer → `SHADER_SAMPLED_READ` |
| `GL_PIXEL_BUFFER_BARRIER_BIT` | `COPY` ↔ `COPY` |

Phase 1 uses a conservative helper (`VkBarriers.computeToAll`, `.all`) everywhere; narrowing them is a
Phase 2 GPU candidate (see OPTIMISATION_LOG.md).

---------------------------------------------------------------------------------------------------

## 6. Shader translation

All shaders live in `src/main/resources/assets/voxy/shaders`. They are compiled at runtime (as on GL,
because Voxy injects defines/replacements per configuration) with shaderc, and every permutation
used by the Vulkan path is also compiled offline with `glslangValidator -V --target-env vulkan1.2`
and validated with `spirv-val` by `tools/validate_shaders.sh` (Phase 1 step 4).

Rules (applied with `#ifdef VOXY_VULKAN` so the GL path keeps compiling the exact same text):

| GL construct | Vulkan GLSL |
|---|---|
| `layout(location = N) uniform T x;` (loose uniforms) | `layout(push_constant) uniform` block (≤128 B) or a UBO |
| `layout(binding = N) buffer/uniform/sampler2D` | `layout(set = 0, binding = N)` – SSBO/UBO/sampler binding numbers in GL are separate namespaces; collisions (e.g. UBO 0 + sampler 0) are renumbered via `VOXY_VULKAN` defines |
| `gl_VertexID` / `gl_InstanceID` | `gl_VertexIndex` / `gl_InstanceIndex - gl_BaseInstance` |
| `gl_BaseInstance(ARB)`, `gl_DrawID(ARB)` | `gl_BaseInstance`, `gl_DrawID` (`shaderDrawParameters` is enabled by Minecraft; needs `GL_ARB_shader_draw_parameters`) |
| `GL_ARB_gpu_shader_int64` | kept (glslang maps it to SPIR-V `Int64`; requires `shaderInt64`) |
| `GL_NV_gpu_shader5`, `GL_NV_fragment_shader_barycentric` | disabled on Vulkan (guarded) |
| subgroup extensions | kept, gated on `VkPhysicalDeviceSubgroupProperties` |
| `#version 460 core` | `#version 460` + `#extension GL_ARB_shader_draw_parameters` where needed |
| `restrict writeonly image2D` with format | same (`shaderStorageImageWriteWithoutFormat` not needed since formats are declared) |

---------------------------------------------------------------------------------------------------

## 7. Order of conversion (one subsystem per commit, `./gradlew build` green each time)

1. Backend detection + abstraction seam + Voxy feature-set mixin + `VkContext`/`VkCommandStream`.
2. Shader compiler (shaderc) + pipeline/descriptor helpers + offline SPIR-V validation script.
3. Buffers, images, samplers, upload/download streams.
4. Geometry data, model store + model texture upload + block-atlas readback.
5. Node manager GPU writes (scatter/memcpy), node cleaner.
6. HiZ buffer, hierarchical traversal.
7. MDIC section renderer (prep, cull raster, cmdgen, prefix sum, translucent build, indirect draws).
8. Render pipeline: depth/stencil setup, final composite, SSAO, chunk-bound renderer.
9. Frame integration (capture in Sodium mixin, LevelRenderer hook), GPU timing, debug renderer.
