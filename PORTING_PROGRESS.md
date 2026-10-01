# Porting progress

Nothing in this file has been run on a GPU. There is no GPU in the build environment: every
Vulkan item below compiles (and its shaders validate with `spirv-val` where noted) but none has
been seen rendering. "Needs visual check" means exactly that.

## Done
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
- `ARCHITECTURE_NOTES.md`: full GL usage inventory and the Vulkan plan.
- Backend selection (`client/core/backend/GpuBackend.java`): resolved from
  `RenderSystem.getDevice()` (`FrontendGpuDevice.backend instanceof VulkanDevice`).
  **Needs visual check** (on both backends: that the right one is picked).
- Vulkan device features: `client/mixin/minecraft/vulkan/MixinVulkanFeatureSets.java` registers
  an optional "Voxy LoD renderer" feature set (`drawIndirectCount`, `fragmentStoresAndAtomics`,
  `vertexPipelineStoresAndAtomics`, `shaderInt64`) so Minecraft enables them when the device
  supports them. **Needs visual check** (check the log line "Enabling optional FeatureSet [Voxy LoD renderer]").
- Vulkan foundation (`client/core/vulkan/`): `VkContext` (wraps Minecraft's `VulkanDevice`,
  VMA, queue, encoder; queries limits/subgroup/driver properties), `VkCommandStream` (records into
  a transient command buffer from Minecraft's pool and splices it in with
  `VulkanCommandEncoder.execute`), `VkBarriers` (glMemoryBarrier translations).
  `Capabilities` is filled from `VkContext` on Vulkan (GL path unchanged).
  **Needs visual check.**
- On Vulkan, GL-only init (`SharedIndexBuffer` GL buffers) is skipped and renderer creation is
  refused with a log message until the Vulkan render system lands, so the game should no longer
  crash on Vulkan at Voxy init. **Needs visual check.**

## In progress
- Backend abstraction for buffers/streams, then the Vulkan render system (see plan below).

## Remaining GL calls (Vulkan equivalents not yet written)
See ARCHITECTURE_NOTES.md §4 for the full per-file list. Every file listed there is still GL-only.

## Blockers
- None yet.

## Open questions
- Classic (non-OIT) transparency: on Vulkan Voxy can only run after the vanilla "Main" pass,
  i.e. after vanilla translucent terrain. See ARCHITECTURE_NOTES.md §1.4.
