# Voxy → Vulkan port (autonomous overnight run)

## Context
- This is a PRIVATE, personal port of Voxy by MCRcortex (All Rights Reserved, see LICENSE.md).
  Never open pull requests, never push anywhere except branches on this repository,
  never modify or remove LICENSE.md.
- Target: Minecraft [VERSION] on Fabric. Never change the Minecraft, Fabric, Sodium,
  Iris or any other dependency version.
- Voxy is built around OpenGL 4.6 (compute shaders, SSBOs, indirect draws).
  Do NOT port it onto Blaze3D / RenderSystem: that loses GPU-driven rendering and is slower.
  Build a native Vulkan backend (LWJGL Vulkan bindings) that uses Minecraft's own Vulkan
  device and command stream.
- If a reference/ folder exists, it contains other people's Vulkan work on Voxy for other
  Minecraft versions. Read it for approach and pitfalls; adapt, don't copy blindly.
  It is not part of the build.
- There is NO GPU in this environment. You cannot run the game or measure frame rates.

## Phase 1: Port
1. First write ARCHITECTURE_NOTES.md: every OpenGL usage (file paths, GL features used)
   and the planned Vulkan equivalent for each. Commit it.
2. Introduce a rendering backend abstraction so the existing OpenGL path keeps working.
   Add the Vulkan backend behind it, selected by Minecraft's active graphics API.
3. Convert one subsystem at a time. `./gradlew build` must pass before every commit.
   Push after every commit.
4. Compile every shader to SPIR-V with glslangValidator and validate with spirv-val.
   Fix all errors.
5. Mark every converted subsystem "needs visual check" in PORTING_PROGRESS.md.

## Phase 2: Optimise (only after Phase 1 builds end to end)
- CPU-side work is measurable here: world ingest, LOD building, meshing, compression,
  storage. For each target:
  - Add a benchmark (JMH or a simple timing harness) and record a baseline first.
  - Add a test proving output is byte-identical before and after.
  - Keep a change only if it is measurably faster AND the test passes. Otherwise revert.
  - Record before/after numbers in OPTIMISATION_LOG.md.
- GPU-side ideas (barriers, synchronisation, descriptor usage, memory allocation,
  pipeline caching): implement only small, clearly safe changes. List larger ideas in
  OPTIMISATION_LOG.md as candidates to profile locally on a real GPU.

## Working rules
- Keep PORTING_PROGRESS.md current: done, in progress, remaining GL calls with file paths,
  blockers, open questions.
- Never claim anything renders correctly or runs faster on the GPU. You can't verify that here.
- Do not stop to ask questions. The human is asleep. If blocked, record it in
  PORTING_PROGRESS.md and move on to the next item.
- Keep going until both phases are complete or everything remaining is blocked.
- Small, revertible commits with clear messages.
