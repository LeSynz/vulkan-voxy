# Porting progress

**Nothing in this file has been run on a GPU.** There is no GPU in the build environment. Every
Vulkan item below compiles, and every Vulkan shader permutation compiles to SPIR-V and passes
`spirv-val` (see "Shader validation"), but none of it has been seen rendering. "Needs visual
check" means exactly that: someone has to run the game on the Vulkan backend and look.

The OpenGL path is meant to behave exactly as before; GL code was moved, not rewritten (see
"GL path changes" for every place it was touched). It also needs a check on a real GL run.

## How to test on a real machine
1. Run the game with Minecraft's Vulkan backend selected.
2. Check the log for `Enabling optional FeatureSet [Voxy LoD renderer]` and
   `Voxy vulkan context: ... voxy feature set enabled: true`. If the set is not enabled the
   device lacks one of `drawIndirectCount`, `fragmentStoresAndAtomics`,
   `vertexPipelineStoresAndAtomics`, `shaderInt64` and Voxy will not create its renderer.
3. Join a world. Expected log: `Creating Voxy vulkan render system`, geometry buffer allocation,
   `Voxy vulkan render system created with ...`.
4. Run with Vulkan validation layers enabled (Minecraft's GPU debug option) — Voxy's command
   buffers are spliced into Minecraft's, so validation messages will name Voxy's pipelines/buffers
   (objects are labelled when debug utils are on).

## Done (all Vulkan items: needs visual check)
- Build setup: Google's Maven Central mirror
  (https://maven-central.storage-download.googleapis.com/maven2/) is the first repository in
  `settings.gradle` (plugins) and `build.gradle` (project, added with `addFirst` so it sits
  ahead of the repositories Loom registers). Maven Central itself was rate-limiting (HTTP 429).
- Build setup: removed Voxy's own `org.lz4:lz4-java:1.8.0` dependency from `build.gradle`.
  Minecraft 26.3 ships `at.yawk.lz4:lz4-java:1.10.1`, a maintained fork that declares the
  `org.lz4:lz4-java` capability, so Gradle refused to put both on the classpath. Voxy now
  compiles against and uses Minecraft's copy (same `net.jpountz.lz4` package). Used by
  `src/main/java/me/cortex/voxy/common/config/compressors/LZ4Compressor.java`.
  Needs a runtime check that LZ4-compressed storage still reads and writes correctly.
- Build setup: JUnit 5 added as a **test-only** dependency (`testImplementation
  org.junit.jupiter:junit-jupiter:5.11.4`, `testRuntimeOnly junit-platform-launcher`) for the
  CPU-only tests (shader validation, byte-identical optimisation checks). No runtime dependency
  or existing dependency version was changed. Vulkan/VMA/shaderc/spvc are Minecraft's own LWJGL
  3.4.3 libraries (already on the classpath, nothing added).
- `ARCHITECTURE_NOTES.md`: full GL usage inventory and the Vulkan plan.
- **Backend selection** — `client/core/backend/GpuBackend.java` (from
  `FrontendGpuDevice.backend instanceof VulkanDevice`). `VoxyRenderSystem.create` picks
  `GlVoxyRenderSystem` or `vulkan/VkVoxyRenderSystem`. Needs visual check (both backends).
- **Vulkan device features** — `mixin/minecraft/vulkan/MixinVulkanFeatureSets.java` adds an
  optional "Voxy LoD renderer" feature set. Needs visual check.
- **Vulkan foundation** (`client/core/vulkan/`): `VkContext` (Minecraft's device, VMA, queue,
  encoder, limits), `VkCommandStream` (transient command buffer from Minecraft's pool, spliced in
  with `VulkanCommandEncoder.execute`; `MixinVulkanCommandEncoder` flushes it before every
  Minecraft submit), `VkBarriers`, `VkGpuBuffer`, `VkMappedBuffer`, `VkImage2D` (always
  `VK_IMAGE_LAYOUT_GENERAL`, like Minecraft's own images), `VkSamplers`, `VkFormats`,
  `VkRendering` (dynamic rendering), `VkGpuFence` (Minecraft's submit timeline). Needs visual check.
- **Shader toolchain** (`vulkan/shader/`): `VkShaderBuilder` (same `#import`/define/replace
  processing as GL), `VkShaderCompiler` (shaderc, Vulkan 1.2; GL built-ins translated; debug
  `printf` stripped), `SpirvBindingRemapper` (GL binding namespaces → one descriptor set:
  SSBO +0, UBO +32, sampler +64, image +96), `SpirvReflection` (spvc), push-descriptor
  `VkPipelineLayout`, `VkComputePipeline`, `VkGraphicsPipeline`/`VkGraphicsState`.
- **Upload/download streams** — `rendering/util/UploadStream`, `DownloadStream` are now shared
  bases; `GlUploadStream`/`GlDownloadStream` hold the unchanged GL code;
  `vulkan/VkUploadStream`/`VkDownloadStream` record `vkCmdCopyBuffer`. Needs visual check.
- **Geometry store** — `section/geometry/VkSectionGeometryData` (+ `ISectionGeometryData`).
  Needs visual check.
- **Node manager GPU writes** — `hierachical/INodeGpuWriter`, `GlNodeGpuWriter` (original GL),
  `VkNodeGpuWriter`. Needs visual check.
- **Node cleaner** — `hierachical/NodeCleaner` (shared), `GlNodeCleaner`, `VkNodeCleaner`.
  Needs visual check.
- **Model store / texture uploads** — `model/ModelStore` (shared), `GlModelStore`,
  `VkModelStore`; block atlas read back asynchronously on Vulkan (`vulkan/VkTextureReadback`),
  model baking waits for it. Needs visual check (block textures on LODs).
- **HiZ** — `vulkan/render/VkHiZBuffer`. Needs visual check (occlusion culling).
- **Hierarchical traversal** — `vulkan/render/VkHierarchicalOcclusionTraverser`. Needs visual check.
- **Section renderer (MDIC)** — `vulkan/render/VkSectionRenderer`, `VkViewport`,
  `VkSharedIndexBuffers` (u16 indices). Needs visual check (terrain, translucency, culling).
- **Render pipeline** — `vulkan/render/VkRenderPipeline` (stencil/depth setup from vanilla depth,
  opaque/translucent targets, final composite into Minecraft's main target with fog/fade),
  `VkSSAO`, `VkFrameTarget`. Needs visual check (composition with vanilla terrain, fog, SSAO).
- **Chunk bound renderer** — `vulkan/render/VkBoundRenderer`; `StreamedBoundStore` and
  `ColumnStreamedBoundStore` are backend-neutral. Needs visual check.
- **Frame integration** — `mixin/sodium/MixinDefaultChunkRenderer` captures the frame on Vulkan;
  `mixin/minecraft/vulkan/MixinLevelRendererVulkan` renders it when Minecraft's Main pass has
  closed (HEAD of `executeOit` / `executeOutline`). Needs visual check.

## Shader validation (Phase 1 step 4)
`./gradlew test` runs `src/test/java/me/cortex/voxy/vulkan/VulkanShaderValidationTest.java`:
every permutation in `vulkan/shader/VkShaderRegistry` (reverse-Z on/off, statistics on/off,
fog modes, SSAO variants, subgroup/simple prefix sum, Intel define on/off) is compiled with
shaderc exactly as at runtime **and** with `glslangValidator -V --target-env vulkan1.2`, and both
outputs are validated with `spirv-val --target-env vulkan1.2`. Current result: **122 stages, 0
errors**. Outputs (sources, .spv, reflected bindings) land in `build/vk-shaders/`.
The reflected bindings were checked against what the Java side binds for every pipeline.

## GL path changes (all behaviour-preserving moves; needs a GL check too)
- `VoxyRenderSystem` → abstract base; old class is `GlVoxyRenderSystem` (unchanged logic).
- `NodeCleaner` → `GlNodeCleaner`, `ModelStore` → `GlModelStore`, AsyncNodeManager GPU block →
  `GlNodeGpuWriter`, Upload/DownloadStream GL calls → `GlUploadStream`/`GlDownloadStream`.
- `Viewport`: GL resources only created on GL; `getRenderList()` returns `IGpuBuffer`.
- `SharedIndexBuffer` generators moved to `IndexData` (same output).
- Shaders: Vulkan-only blocks under `#ifdef VOXY_VULKAN` (push constants/UBOs instead of loose
  uniforms, no `gl_DepthRange`), `prep.comp` cube first index via `CUBE_FIRST_INDEX` (defaults to
  the old GL value). The text GL compiles is otherwise unchanged.

## In progress
- Nothing mid-edit. Phase 1 and Phase 2 are complete as far as this environment allows (no GPU).
  Phase 2 results and reverted attempts are in `OPTIMISATION_LOG.md`.
- Phase 2 touched shared (GL and Vulkan) CPU code, all covered by byte-identical tests:
  `common/world/other/Mipper.java`, `Mapper.java` (opacity cache), `common/world/SaveLoadSystem3.java`
  (serialize palette table), `common/voxelization/DataLayerLightingSupplier.java` (new),
  `ILightingSupplier.java`, `WorldConversionFactory.java`, `common/world/service/VoxelIngestService.java`.
  These still need an in-game check (world loads, existing saves still read).

## Remaining GL-only code (no Vulkan equivalent yet)
- `core/util/GPUTiming.java` — GPU timestamp queries (debug overlay). Vulkan path has no GPU
  timings yet (`vkCmdWriteTimestamp2` + query pool would replace it).
- `core/rendering/hierachical/DebugRenderer.java` — node outline debug renderer (not wired into
  either render system at the moment).
- `core/rendering/util/PrintfDebugUtil.java`, `gl/shader/PrintfInjector.java` — shader printf
  debugging; on Vulkan `printf` statements are stripped.
- `core/rendering/util/HiZBuffer2.java`, `RawDownloadStream.java`,
  `rendering/bounding/ChunkBoundStore.java`, `ExactBoundStore.java`,
  `rendering/section/geometry/BasicSectionGeometryManager.java` — unused alternatives.
- `core/IrisVoxyRenderPipeline.java`, `client/iris/*`, `mixin/iris/*` — see Blockers.
- `mixin/nvidium/MixinRenderPipeline.java` — Nvidium is GL/NV-mesh-shader only.
- `mixin/minecraft/util/MixinGlDebug.java` — GL debug log filter (GL only by nature).

## Blockers
- **Iris shader packs on Vulkan**: Iris 1.11.6 renders through OpenGL; there is no Vulkan Iris
  pipeline to integrate with. The Vulkan backend always uses the normal pipeline.
- **FREX flawless frames on Vulkan**: the GL path loops inside one frame until GPU results come
  back (`glFinish`). On Vulkan GPU work only completes after Minecraft submits, so those loops are
  disabled (one step per frame); flawless-frame captures will not wait for LOD completion.

## Open questions / known differences to check on a real GPU
- Classic (non-OIT) transparency: on Vulkan Voxy runs after the vanilla Main pass, i.e. after
  vanilla translucent terrain, so far LODs are composited after near water. With improved
  transparency (OIT) the order matches GL. See ARCHITECTURE_NOTES.md §1.4.
- No memory budget query on Vulkan: the geometry buffer is sized from
  `min(maxStorageBufferRange, maxMemoryAllocationSize, deviceLocalHeap - 2.5GB)` and halved on
  allocation failure. `-Dvoxy.geometryBufferSizeOverrideMB` still works.
- Winding: `VkGraphicsState` assumes GL CCW == Vulkan CW under Minecraft's no-flip convention
  (only the chunk-bound renderer culls). If bound culling looks inverted, swap `cullBackGlCW`.
- `VkTextureReadback` assumes Minecraft's block atlas is RGBA8 with `COPY_SRC` usage (it is in
  26.3: usage 15).
