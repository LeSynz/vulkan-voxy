# Optimisation log

Phase 2 (CPU side only). GPU-side ideas are listed at the end as candidates to profile on a real
GPU; none of them has been measured.

## Method
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

## Results

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

## GPU-side candidates (not measured — profile on a real GPU)
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
