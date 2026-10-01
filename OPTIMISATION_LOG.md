# Optimisation log

Two parts: **Part 1** is the in game work on a real GPU (RTX 3070) after the port ran, **Part 2** is the earlier CPU
micro-benchmark work done without a GPU (synthetic data, a cloud VM).

# Part 1: in game, real GPU

## Environment and method
- AMD Ryzen 7 5700X (8c/16t), NVIDIA GeForce RTX 3070 (driver 595.91.07), 31 GB RAM, Ubuntu 26.04, Wayland, OpenJDK 25.0.4.
  Minecraft 26.3 + Fabric + Sodium 0.9.2 + Voxy Vulkan only (dev client, `./gradlew runBench`), 1920x1080, vsync off,
  vanilla render distance 4, improved transparency, Voxy config of the author (render distance ~54 chunks, SSAO auto = basic).
- Scenario (`client/bench/VoxyBenchmark`): the author's survival world (1.4 GB, 104 MB Voxy database, copied fresh for every run),
  45 s settle after joining, then a 90 s flight (spectator, height 130, circle of radius 700 at 80 blocks/s, the camera yaws with it) which
  makes Sodium and Voxy load and cull continuously. Every frame time is recorded. Before the flight 4 stationary
  screenshots at fixed poses (time/weather frozen, HUD hidden) are saved and compared pixel by pixel with the previous build.
- Reproducibility: three runs of the unmodified `main` gave 867.6 / 865.2 / 864.2 average fps (0.4% spread), the
  tail percentiles vary by ~5% (they are dominated by garbage collection, see below).
- Safety net for GPU changes: Khronos validation layer with synchronization validation on top of the benchmark at several window
  sizes (see README), plus the screenshot comparison. Every kept change was clean on both.
- Tools that were built for this and stay in the repo: the benchmark, GPU timestamp timers around each Voxy frame section
  (`-Dvoxy.gpuTimers=true`), per frame barrier/render pass counters, `-Dvoxy.stats=true`, JFR (`-Dbench.jfr=`), a dump of real world
  sections + real baked model tables (`-Dvoxy.bench.dump`) with a `RealMeshingJMH` benchmark and `RealMeshingIdentityTest`.

## Result
| 1080p flight, 3 runs each | before (`main`) | after |
|---|---|---|
| average fps | 867.6 / 865.2 / 864.2 (mean 865.7) | 931.8 / 927.0 / 913.7 (mean **924.2, +6.8%**) |
| median frame | 1.08 ms | 1.01-1.02 ms |
| 1% low fps | 240-251 | 247-261 (+4%) |
| 0.1% low fps | 89-92 | 89-94 (unchanged, garbage collection) |
| Voxy GPU time per frame (timestamps) | 0.659 ms | 0.605 ms (-8%) |
| render passes / barriers per frame | 16.7 / 66.3 | 7.0 / 55.5 (49 with the later batching change) |
Screenshots of the poses are identical to the previous build within noise (mean abs difference < 0.15/255 per channel, the
differences are time based noise). Later runs include a further small batching change that was only measured with timers
on (+1.4%, see below); the table above does not include it.

## Where the frame time goes
- Without Voxy the same flight runs at ~2150 fps (0.46 ms); with Voxy 866 fps (1.08 ms). Voxy costs 0.62 ms per frame.
- Voxy's GPU work is 0.659 ms per frame by timestamps (so the game is **GPU bound**, Voxy's CPU time on the render thread is only ~0.06 ms:
  JFR shows `VoxyRenderSystem.renderFrame` at 9% of the render thread's samples).
- GPU time per section (1080p, ms), before -> after:

| section | before | after |
|---|---|---|
| depth/stencil setup (vanilla depth copy) | 0.024 | 0.020 |
| opaque terrain draw | 0.321 | 0.313 |
| HiZ pyramid | 0.054 | 0.032 |
| hierarchical traversal (5 dependent dispatches) | 0.052 | 0.046 |
| draw call generation (prep, occlusion test raster, cmdgen, translucent gen) | 0.066 | 0.057 |
| temporal (newly visible) draw | 0.036 | 0.036 |
| SSAO | 0.051 | 0.048 |
| translucent draw | 0.016 | 0.017 |
| composite into the main target | 0.038 | 0.036 |
| **total** | **0.659** | **0.605** |

- The opaque draw is vertex bound: replacing its fragment shader with a constant colour only removes 23% of it, skipping the draw entirely
  gives +26% fps (the upper bound for all terrain draws). 530k quads (~1M triangles) per frame at LOD 0.

## Changes that were kept
1. **The cost of an INDIRECT_COMMAND_READ access in a barrier** (largest single finding). Measured by adding 100 back to back
   barriers per frame with different masks: all of storage write -> storage read/write, adding the DRAW_INDIRECT stage, UNIFORM_READ,
   SAMPLED_READ or TRANSFER stages/accesses cost nothing (100 barriers = 0.0006 ms), but **adding
   `VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT` to the destination mask costs 1.9 us per barrier** (100 = 0.19 ms), and the generic
   `MEMORY_READ|MEMORY_WRITE` masks (which include it) cost the same (full ALL_COMMANDS + MEMORY: 2.5 us). Voxy used those masks in about
   60 of its 66 barriers per frame. Now `VkBarriers` uses explicit read/write masks, and the indirect bit is only added in front
   of the 8 barriers that actually precede an indirect draw/dispatch consuming buffers written on the GPU (buffers are flagged with
   `VkGpuBuffer.indirectUse()`, copies/fills to them add the bit). Voxy GPU 0.742 -> 0.713 ms, +2.6% fps, validation clean.
2. **HiZ pyramid with compute** (`hiz/hiz.comp`): the old build used one fullscreen render pass + one attachment barrier per mip level
   (11 passes). Now 3 compute dispatches build 4 levels each in shared memory (same reduction maths incl. the hole patching, an R32F image
   instead of a depth image). 0.054 -> 0.032 ms, render passes 16.7 -> 7 per frame.
3. Smaller: the occlusion test pass renders depth only (no colour attachment), depth/stencil targets are cleared by `loadOp CLEAR`
   instead of transfer clears (-1 pass-sized clear and its barriers), the per frame counter clears are one transfer group, the scene uniform is
   uploaded once per frame (7 fewer barriers; measured -0.004 ms, +1.4% fps with the timers on).
4. **Startup**: `VkShaderCache` caches the compiled, binding remapped SPIR-V on disk (`.voxy/shader-cache`, keyed by a hash of the exact
   source) and one `VkPipelineCache` persists the driver's pipelines. Creating the render system on joining a world:
   413 ms (shaderc 248 ms for 23 shaders) -> **173 ms** with a warm cache (the SPIR-V loads in 1.7 ms; 17 pipelines
   create in 3.3 ms). `-Dvoxy.noShaderCache=true` turns it off; old files are pruned.
5. The shutdown hang and memory leak fixes from the first test runs on the 3070 (`ModelBakerySubsystem` loop, texture readback buffer) and
   forcing improved transparency (see PORTING_PROGRESS.md) are not performance work but were needed to run the benchmark at all.

## Things that were measured and did not help (not kept / no effect)
- Depth only occlusion test pass: -0.0016 ms (noise level, kept because it is correct).
- Decoding the quad data with 32-bit instead of 64-bit integers in the vertex shader: identical timing and pixels.
- Fewer transfer groups beyond the above: each group is cheap now, the remaining ~49 barriers cost <1 us each.
- Mesher micro-optimisations on real data (see below): not CPU bound.

## Stutters: the worst frames are garbage collection
- 145 collections in a 135 s run (one per second), pauses 5-14 ms, against a 1 ms frame: those are exactly the 0.1% low frames. The same
  flight **without Voxy** has similar pauses (92 GCs, avg 8.1 ms, max 21 ms), so most of it is Minecraft/Sodium: 53 GB of sampled allocations
  over the run, 27% from Sodium's `Tree.<init>` (a ~100 MB/s `long[]` per cull task), Voxy's own share is ~6-10% (descriptor objects, upload
  stream bookkeeping, biome id strings during ingest). G1's time is dominated by scanning the remembered set and copying survivors.
- Changing the JVM collector helps much more than anything the mod can do. Same scenario, 70 s flights, 1080p:

| JVM arguments | avg fps | 1% low | 0.1% low | worst frame |
|---|---|---|---|---|
| default G1 (3 runs) | 868 / 861 / 891 | 228 / 230 / 228 | 80 / 87 / 86 | 29 / 21 / 30 ms |
| `-XX:+UseZGC` (3 runs) | 878 / 865 / 857 | 258 / 244 / 244 | 117 / 121 / 117 | 30 / 22 / 18 ms |
| `-XX:+UseZGC -Xms2G` | 872 | 262 | 125 | 19 ms |
| `-XX:+UseShenandoahGC` | 853 | 234 | 106 | 37 ms |
| G1 `-XX:MaxGCPauseMillis=4 -Xms2G` | 874 | 236 | 106 | 26 ms |

  Recommendation (README): `-XX:+UseZGC -Xms2G` (+40% on the 0.1% low, +7-15% on the 1% low, same average fps). A mod cannot set JVM flags.

## Loading
- Joining the author's world: the first section is on the GPU 0.2-0.4 s after the render system exists, 90% of the 4.5k sections
  within 1.4-1.8 s (~2200 sections/s), the mesh queue is empty after ~2 s. Render system creation itself is 173 ms (was 413).
- Real data, 698 real LOD 0 sections with the real model tables: `RenderDataFactory.generateMesh` takes 176 us per section (the synthetic data
  of Part 2 was 5x heavier). CPU profile of a cold database run (ingesting chunks while flying): meshing 55% of Voxy's worker time
  (`prepareSectionData` 24%, face generation 26%), ingest 24%, saving 13% (`SaveLoadSystem3.serialize`). It is spread out; a mesher
  rewrite for a few percent was not worth the risk. Voxy's workers are not the limit when new chunks are loaded (chunk arrival from the
  integrated server is), and the existing LOD database loads faster than the player can see it.

## Candidates not done (need real design work or visual trade offs)
- One persistent kernel for the 5 hierarchical traversal dispatches (46 us, latency bound).
- Draw opaque sections front to back (early-z for the 23% fragment share; counting sort by distance already exists for translucents).
- A compute pre-pass culling quads before the vertex stage / mesh shaders (the opaque pass is vertex bound at 0.31 ms).
- Skipping the temporal pass when empty (`VK_EXT_conditional_rendering`), merging the depth setup and composite passes, half resolution SSAO.
- Reducing Voxy's allocation rate further (~6-10% of the total allocation rate, not worth it by itself).
- A larger LOD quality/performance lever already exists in the config (sub division size): LOD 0 is used out to very far distances.

# Part 2: CPU micro-benchmarks (earlier, synthetic data)

The rest of this file is the earlier work done in a cloud VM without a GPU: JMH benchmarks plus byte identical JUnit tests against a
verbatim copy of the original code. Its environment is different (4 vCPU Xeon).

## Method (CPU micro-benchmarks)
- Environment: 4 vCPU Intel Xeon @ 2.80GHz (shared cloud VM), 15 GB RAM, Temurin 25.0.4.
  Shared hardware → expect a few % run-to-run noise; only changes well outside the JMH error
  bars are kept.
- Benchmarks: JMH (`src/jmh/java/me/cortex/voxy/jmh/`), run with
  `./gradlew jmh -PjmhIncludes=<BenchmarkClass>`; 2 forks × 5 measurement iterations unless noted.
  Where possible each benchmark also runs a verbatim copy of the pre-optimisation code
  (`src/test/java/me/cortex/voxy/opt/reference/`) in the same JVM for an A/B comparison.
- Byte-identical tests: JUnit (`src/test/java/me/cortex/voxy/opt/`), run by `./gradlew test`.
  Each compares the optimised code against the reference copy of the original on deterministic
  synthetic data (`TestWorlds`), including adversarial patterns. Minecraft's registries are
  bootstrapped in-process (`McBootstrap`) so real block states/opacities are used.
- Rule: a change is kept only if it is measurably faster and the identity test passes.

## Summary (CPU micro-benchmarks)
| target | result |
|---|---|
| LOD building (mipping) | kept #1 + #2: terrain −20%, noise −17% per section |
| Storage (serialization) | kept #3: serialize −42% terrain / −19% sparse / −24% dense; #4, #5 reverted |
| World ingest (conversion) | kept #6: −60% per section; #7 (world insert) reverted |
| Compression | measured only (#8): every remaining change would alter stored bytes |
| Meshing | measured + profiled (#9); #10 reverted, no change kept |
| GPU side | no change made; candidates listed at the end (none could be measured here) |

All kept changes have a byte-identical JUnit test against a verbatim copy of the original code
(`./gradlew test`). All numbers are from the CPU-only build environment.

## Results (CPU micro-benchmarks)

### 1. LOD mipping — `Mipper.mip` uniform fast path — KEPT
File: `src/main/java/me/cortex/voxy/common/world/other/Mipper.java`.
If all 8 children are the same value the result is that value (non-air ties resolve to `I111`;
averaging 8 identical light values reproduces the input light). Returned before any lookups.

Benchmark `MipperJMH` (one `WorldVoxilizedSectionMipper.mipSection`, µs/op, lower is better):

| data | before (current) | before (reference) | after (current) | after (reference, same run) |
|---|---|---|---|---|
| terrain | 13.761 ± 0.467 | 14.924 ± 1.102 | 12.183 ± 0.564 | 14.142 ± 0.627 |
| noise (worst case, nothing uniform) | 24.965 ± 1.111 | 25.804 ± 1.977 | 24.518 ± 1.174 | 24.873 ± 1.512 |

Terrain −14% vs. the reference in the same run; noise unchanged (within error, the extra
comparisons cost nothing measurable). Identity: `MipperIdentityTest` (600 random sections,
200 uniform/cell-structured sections, 2M single `mip` calls with 1–3 distinct inputs).

### 2. LOD mipping — flat opacity array in `Mapper` — KEPT
File: `src/main/java/me/cortex/voxy/common/world/other/Mapper.java`.
`getBlockStateOpacity(int)` read `ObjectArrayList<StateEntry>.get(id).opacity` (list bounds
check + entry dereference) for every non-air child. Entries are append-only and immutable, so a
volatile `int[]` copy is kept and rebuilt (under the existing block lock) when an id beyond it
is requested; unknown ids still throw exactly as before.

| data | before (after #1) | after #2 (current) | reference in same run (also uses the cache) |
|---|---|---|---|
| terrain | 12.183 ± 0.564 | 10.967 ± 0.426 | 12.655 ± 0.527 |
| noise | 24.518 ± 1.174 | 20.647 ± 0.689 | 20.227 ± 0.615 |

Noise −16%, terrain −10% vs. the previous run. Combined #1+#2 vs. the original baseline:
terrain 13.76 → 10.97 µs (−20%), noise 24.97 → 20.65 µs (−17%). Identity: same test as #1.

### 3. Storage — `SaveLoadSystem3.serialize` specialised LUT — KEPT
File: `src/main/java/me/cortex/voxy/common/world/SaveLoadSystem3.java`.
The block-value → palette-index map was a fastutil `Long2ShortOpenHashMap` (`putIfAbsent(key,
size())`, cleared per section). Replaced by a small open-addressing table (long keys, short
values, linear probing, fastutil's `HashCommon.mix` hash) that hands out indices in first-insertion
order like before and uses a generation stamp instead of clearing. Output bytes are identical.

Benchmark `SaveLoadSystemJMH` (one 32³ section, µs/op):

| data | before (current) | before (reference) | after (current) | reference in same run |
|---|---|---|---|---|
| terrain | 91.789 ± 1.862 | 91.402 ± 7.307 | 52.578 ± 1.887 | 90.889 ± 3.288 |
| sparse (old SaveLoadJMH data) | 300.484 ± 9.219 | 296.138 ± 8.322 | 243.062 ± 6.394 | 300.357 ± 8.758 |
| dense, 300 distinct | 213.572 ± 7.504 | 216.212 ± 11.255 | 161.780 ± 4.865 | 212.873 ± 9.685 |

terrain −42%, sparse −19%, dense −24%. Identity: `SaveLoadIdentityTest` (serialized bytes and
deserialized data/metadata vs. the original, levels 0–4, terrain/sparse/dense incl. 20000
distinct values to exercise table growth, all-zero sections, wrong-key path).

### 4. Storage — second most-recent-value slot in serialize — REVERTED
Keep the previous distinct value + mapping as well, so alternating runs (air, block, air) skip
the table. Identity test passed but it was not faster:
terrain 50.156 ± 3.099 (vs 52.578), sparse 254.081 ± 13.042 (vs 243.062), dense 175.542 ±
8.375 (vs 161.780) µs/op. Reverted.

### 5. Storage — fuse the non-air count into the deserialize decode loop — REVERTED
Single pass instead of decode + count. Identity test passed but it was much slower
(the separate count loop vectorises): 38.725 ± 2.045 vs. reference 26.856 ± 1.576 µs/op
(terrain; sparse/dense the same). Reverted. Deserialize stays at the original ~27 µs/op.

### 6. World ingest — single lighting supplier class + bulk light fill — KEPT
Files: `common/voxelization/DataLayerLightingSupplier.java` (new), `ILightingSupplier.java`
(`supplyAll` default method), `WorldConversionFactory.java`, `common/world/service/VoxelIngestService.java`.
`VoxelIngestService` built one of three lambdas per section and `WorldConversionFactory.convert`
called it once per voxel (4096 megamorphic interface calls, each doing `DataLayer.get`).
Now one class (`DataLayerLightingSupplier`, same null/empty/clamp semantics) fills all 4096 light
bytes from the two nibble arrays in one pass, and the conversion loop reads that array. The
world importer keeps its own lambda through the default `supplyAll` (unchanged behaviour).
Needs access to `DataLayer.data`/`defaultValue` (access widener).

Benchmark `ConversionJMH` (one 16³ section from a real `PalettedContainer<BlockState>`, mixed
light modes, µs/op):

| step | current | reference (original code) in same run |
|---|---|---|
| baseline: new supplier class only, convert unchanged (per voxel `supply`) | 21.139 ± 1.447 | 44.281 ± 2.063 |
| bulk fill via int scratch arrays (tried, slower than the line above → replaced) | 25.888 ± 0.964 | 45.034 ± 2.339 |
| bulk fill combining nibble bytes directly (kept) | 18.159 ± 0.811 | 45.296 ± 3.465 |

Conversion −60% vs. the original (45.3 → 18.2 µs per section). Note that the reference uses
the original three-lambda supplier, which is what made its call site megamorphic.
Identity: `ConversionIdentityTest` (400 sections: palettes of 0–300 random extra states incl.
global-palette sized ones, biome borders, light modes none/sky/sky+block/filled-default layers;
voxel arrays and non-air counts vs. the original convert + original lambdas; plus every
per-voxel `supply` value vs. the original lambdas).

### 7. World ingest — row-wise `WorldUpdater.insertSectionLvlIntoWorld` (level 0) — REVERTED
Process each 16-voxel x row with `Arrays.mismatch` + `System.arraycopy` (vectorised intrinsics)
instead of the hand-unrolled 4-voxel loop. Identity test (`WorldUpdaterIdentityTest`, 3000
inserts over levels 0–4, incl. no-change inserts) passed, but `WorldInsertJMH` (same data as the
existing `WorldUpdaterJMH`) showed no gain: baseline 15.773 ± 0.928 (current) / 15.329 ± 1.006
(reference); after 16.139 ± 1.797 vs. reference 14.819 ± 1.298 µs/op. The insert is bound by
memory traffic into the 256 KB world section, not by the loop. Reverted; the benchmark and the
identity test stay for future attempts.

### 8. Storage — compression (ZSTD / LZ4) — assessed, NO CHANGE
Files: `common/config/compressors/ZSTDCompressor.java`, `LZ4Compressor.java`.
Both are thin wrappers over native code (LWJGL zstd with thread-local contexts and scratch
buffers; Minecraft's lz4-java native instance). Every remaining knob (level, strategy, window,
dictionary, frame/checksum flags) changes the compressed bytes, which the byte-identical rule does
not allow, so the compressors were only measured. Benchmark `StoragePipelineJMH` (one 32³
section, µs/op; "save" = serialize + compress, "load" = decompress + deserialize):

| compressor / data | serialized → compressed size | compress | decompress | save (current) | save (original serializer) | load (current) |
|---|---|---|---|---|---|---|
| ZSTD level 1 (default) / terrain | 66001 → 1915 B | 57.3 ± 2.8 | 31.5 ± 0.6 | 124.7 ± 4.2 | 165.6 ± 7.1 | 67.1 ± 5.3 |
| ZSTD level 1 / dense300 | 67952 → 45921 B | 92.8 ± 3.8 | 47.8 ± 1.8 | 264.4 ± 12.9 | 312.5 ± 13.2 | 84.4 ± 4.1 |
| LZ4 / terrain | 66001 → 4941 B | 30.3 ± 0.9 | 40.3 ± 1.7 | 94.2 ± 5.4 | 131.6 ± 8.8 | 79.7 ± 4.6 |
| LZ4 / dense300 | 67952 → 68208 B | 15.7 ± 0.7 | 10.4 ± 0.4 | 194.2 ± 17.5 | 238.3 ± 13.1 | 44.1 ± 5.2 |

With the default ZSTD level 1 the whole section save is −25% (terrain) / −15% (dense) thanks to #3.
Compression is ~45% of the terrain save time after #3. Changing the default level or compressor
is a format/size trade-off for the maintainer, not a byte-identical optimisation, so it is
only noted here.

### 9. Meshing — `RenderDataFactory.generateMesh` — baseline
Benchmark `MeshingJMH` and test `MeshingIdentityTest` run the mesher headless: `MeshingFixture`
builds a `ModelFactory` whose model tables (`idMappings`, `metadataCache`, `fluidStateLUT`, the
only state the mesher reads) hold synthetic metadata in the exact bit layout
`ModelFactory.processTextureBakeResult` writes (solid, biome-tinted, water, lava, leaves, glass,
double-sided plants, waterlogged blocks, light emitters + 300 random well-formed models), and a
`WorldEngine` over an in-memory `SectionStorage` so neighbour faces are fetched through the real
section tracker. The identity test compares position, child mask, AABB, buffer offsets and every
geometry byte against a verbatim copy of the original (`ReferenceRenderDataFactory`) for terrain
at levels 0–2 (~5k quads per section), dense random (~91k quads per section) and sparse random sections.

Baseline (µs/op per 32³ section, 2 forks × 5 iterations):

| data | current (= original) | reference |
|---|---|---|
| terrain | 920.0 ± 47.0 | 889.6 ± 36.3 |
| noise (dense random) | 8545.7 ± 637.5 | 8283.1 ± 410.6 |
| sparse (90% air random) | 1788.6 ± 79.2 | 1797.1 ± 65.4 |

Profile (JMH `stack` profiler, terrain, share of mesher samples): `ScanMesher2D`
(`putNext`/`putNext0`/`emitRanged`/`skip`, the greedy quad merger) ≈ 51%, `prepareSectionData`
(per-voxel model lookup) ≈ 25%, `acquireNeighborData` ≈ 6%, face generation loops the rest.
Run with `./gradlew jmh -PjmhIncludes=MeshingJMH -PjmhProfilers=stack`.

### 10. Meshing — reuse the previous voxel's lookup in `prepareSectionData` — REVERTED
The in-code TODO ("cache the results of this, then link it to `block`"): if a voxel's raw value
equals the previous voxel's, reuse its packed quad data, metadata and mask bits instead of the
`idMappings`/`metadataCache` lookups and packing. Identity test passed, but it was not measurably
faster: terrain 845.3 ± 45.8 vs. reference 880.0 ± 46.0 µs/op (−4%, error bars overlap), noise
8433 ± 337 vs. 8251 ± 411, sparse 1941 ± 103 vs. 1751 ± 70 (+11% slower; the extra compare
mispredicts on random data). Reverted. The meshing benchmark/test stay; the identity test now also
covers the "model not baked yet" path (`IdNotYetComputedException` id and neighbour mask must match).

Larger meshing ideas, not attempted because they cannot be shown faster without changing the hot
loop structure of the hand-tuned merger, or would only pay off in-game (for profiling on a real install):
- Cache per-section directional face masks across rebuilds (the in-code TODO at the top of
  `RenderDataFactory`); rebuilds after single-block updates would skip unchanged axes.
- `acquireNeighborData` copies 6×1024 longs through the section tracker per mesh; reading the
  neighbour arrays on demand would avoid the copies (needs care with the tracker's reuse).

## GPU-side candidates written before a GPU was available (superseded by Part 1)
No GPU-side change was made in Phase 2. The smallest candidate, an in-memory `VkPipelineCache`,
is not clearly safe either: `VkContext` has no teardown hook, so the cache would show up as a
leaked object when Minecraft destroys the device (validation layers report it). It needs a
proper shutdown path first.
- Barriers: Phase 1 uses conservative `VkBarriers` (e.g. `allToTransfer`/`transferToAll` around
  every buffer fill/update, ALL_COMMANDS full barriers at the start/end of Voxy's command buffer).
  Narrow them to the actual producer/consumer stages, and batch the per-TLN `vkCmdUpdateBuffer`
  writes in `VkHierarchicalOcclusionTraverser` into one upload-stream copy.
- Pipeline cache: pipelines are created without a `VkPipelineCache`; a persisted cache would cut
  renderer start-up time (shader compile is runtime because of define permutations).
- Descriptor usage: push descriptors are re-pushed on every bind; most bindings are static per
  pipeline and could use a persistent descriptor set + only push the per-frame ones.
- Memory: geometry buffer is one dedicated VMA allocation sized without a budget query; enabling
  `VK_EXT_memory_budget` through the Voxy feature set would allow GL-like free-memory sizing.
- Indirect count: `maxDrawCount` for the terrain MDIC draw is the configured cap; a tighter
  bound from the previous frame's count could reduce command processor work on some drivers.
