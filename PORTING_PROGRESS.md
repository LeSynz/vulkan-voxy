# Porting progress

Voxy Vulkan is Voxy 0.2.20-beta (by MCRcortex) ported to a native Vulkan backend for Minecraft 26.3 on
Fabric. It uses Minecraft's own Vulkan device and splices its command buffers into Minecraft's stream.
**The OpenGL backend has been removed**: Voxy Vulkan only runs when Minecraft itself runs on its Vulkan
backend (if not, it logs an error and disables itself instead of crashing).

## Verification status
Verified on one machine: Linux (Wayland), NVIDIA GeForce RTX 3070, driver 595.91, Minecraft 26.3 dev client with
Sodium 0.9.2, 1920x1080.

- The mod starts, creates its renderer, loads the LODs of an existing world and renders them (looked at the
  screenshots of the benchmark scenario; compared pixel by pixel against the previous builds after every GPU change).
- Khronos validation layer with **synchronization validation** (core + sync) over benchmark runs at 5 window sizes
  (400x300 .. 2560x1440): no Voxy errors. The only messages are Minecraft's own `vkCmdWriteBufferMarker2AMD`
  hazard (AMD buffer marker feature set, Minecraft internal) and objects left over at device destruction.
- Lifecycle stress (`-Dvoxy.bench.stress=true`): 6 renderer shutdown/recreate cycles and window resizes (1920x1080 -> 1000x600 ->
  640x360 -> 1920x1080) while rendering, under validation: no errors, no leaked tracked objects.
- Everything else (other GPUs and drivers, AMD/Intel, the SSAO modes BETTER/BEST, the fog modes other than
  the default, classic transparency, resizing/fullscreen toggles, other resource packs) has **not** been tested.

## What is there
- Backend: `client/core/backend/GpuBackend` (checks Minecraft runs on Vulkan), `client/core/vulkan/*`
  (`VkContext`, `VkCommandStream`, `VkBarriers`, `VkGpuBuffer`, `VkMappedBuffer`, `VkImage2D`, `VkRendering`, ...).
- Shaders: GLSL compiled with shaderc at runtime (`vulkan/shader`), GL binding namespaces remapped into one
  descriptor set (`SpirvBindingRemapper`), push descriptors. `./gradlew test` compiles every permutation with shaderc and
  glslangValidator and validates with spirv-val (118 stages, 0 errors).
- Renderer: `client/core/VoxyRenderSystem` + `vulkan/render/*` (HiZ, hierarchical traversal, MDIC terrain, SSAO,
  chunk bound renderer, composite). Frame integration: `mixin/minecraft/vulkan/*`, `mixin/sodium/MixinDefaultChunkRenderer`.
- Minecraft Vulkan device features Voxy needs (`drawIndirectCount`, `fragmentStoresAndAtomics`,
  `vertexPipelineStoresAndAtomics`, `shaderInt64`) are requested through an optional feature set
  (`MixinVulkanFeatureSets`).
- Improved transparency is forced on while the renderer is active (`MixinGameRendererVulkan`): Voxy runs after
  Minecraft's main pass, with classic transparency vanilla water would hide the LODs behind it.
- Tooling: scripted in-game benchmark (`./gradlew runBench`, see README), GPU timestamp timers
  (`-Dvoxy.gpuTimers=true`), a real data dump for the CPU benchmarks.

## Removed in the Vulkan only change
All OpenGL classes and shader branches, the MDIC GL renderer, GL HiZ/SSAO/pipelines/streams, GPU timing, the
Iris pipeline integration, Nvidium integration, the GL debug log filter, unused shaders. The mod id (`voxy`),
the config file name and all asset paths are unchanged.

## Known limitations / open questions
- **Iris shader packs** cannot work: Iris renders through OpenGL.
- **FREX flawless frames**: the loops that wait inside one frame for GPU results are disabled (one step per frame).
- Classic (non OIT) transparency is not supported, see above. A true fix needs Voxy to run inside the Main pass before
  translucents (splitting the pass); not attempted.
- No memory budget query: the geometry buffer is `min(maxStorageBufferRange, maxMemoryAllocationSize,
  deviceLocalHeap - 2.5GB)`, halved on allocation failure. `-Dvoxy.geometryBufferSizeOverrideMB` overrides it.
- Winding: `VkGraphicsState.cullBackGlCW` assumes GL CCW == Vulkan CW under Minecraft's no flip convention
  (only the chunk bound renderer culls; no artefacts in the benchmark screenshots, not verified beyond that).
- The static `UploadStream`/`DownloadStream` buffers are never freed, validation reports them when the device is destroyed.
- Vanilla render distances below 3 can cause rare culling artefacts near the screen edge (warning shown in game).

## Performance
See `OPTIMISATION_LOG.md` (measured on the machine above).
