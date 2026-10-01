Voxy Vulkan is a private Vulkan port of Voxy (by MCRcortex), an LoD rendering mod for minecraft.

It is Vulkan only: it needs Minecraft 26.3 running on its Vulkan graphics backend (and Sodium, see `gradle.properties`
for the exact versions). If Minecraft runs on OpenGL the mod logs an error and disables itself.
Iris shader packs are not supported (Iris is OpenGL only).

# Building
`./gradlew build` (runs the unit tests, which include compiling and validating every shader). The jar ends up in `build/libs/`.

# Recommended JVM arguments
The worst frames in this mod's benchmark are garbage collection pauses of Minecraft and Sodium themselves, not Voxy.
Switching the JVM to ZGC measurably helps (same scenario, 70s flights, RTX 3070, 1080p): 0.1% low frame rate
86 -> 120 fps, 1% low 229 -> 244-262 fps, worst frame 21-30ms -> 18-21ms, average fps unchanged.

    -XX:+UseZGC -Xms2G

(see `OPTIMISATION_LOG.md` for the table). Add them to your launcher's JVM arguments.

# Benchmarking
`./gradlew runBench` starts a dev client, joins a world, lets it settle, flies a scripted circle and writes frame time
statistics (average, percentiles, 1%/0.1% lows, stutter counts, Voxy GPU time per section, barrier and render pass
counts, loading metrics) to a json file. It needs a world in `run-bench/saves/<name>`:

    ./gradlew runBench -Dbench.world=<name> -Dvoxy.bench.out=result.json -Dvoxy.bench.label=mytest \
        [-Dvoxy.bench.settle=45] [-Dvoxy.bench.duration=90] [-Dvoxy.bench.poses=false] \
        [-Dbench.width=1920] [-Dbench.height=1080] [-Dbench.jvmargs="-XX:+UseZGC"] [-Dbench.jfr=file.jfr]

Options of `run-bench/options.txt` and `run-bench/config/voxy-config.json` are used as is. The benchmark class is
`client/bench/VoxyBenchmark` (inactive unless `-Dvoxy.bench=true`, which `runBench` sets). Stationary screenshots at 4
fixed poses are saved to `run-bench/screenshots/` (`bench-<label>-poseN.png`) for comparing builds.
`-Dvoxy.bench.stress=true` instead runs a lifecycle stress test (renderer teardown/recreate and window resizes while rendering).
`-Dvoxy.bench.dump=<file>` additionally dumps real world sections plus the real baked model tables, which
`VOXY_REAL_DATA=<file> ./gradlew jmh -PjmhIncludes=RealMeshingJMH` and `RealMeshingIdentityTest` use.

Useful system properties: `-Dvoxy.gpuTimers=true` (GPU timestamps per frame section, shown in the debug overlay),
`-Dvoxy.stats=true` (traversal statistics), `-Dvoxy.noShaderCache=true` (ignore the on disk SPIR-V/pipeline cache in
`.voxy/shader-cache`).

# Validation layers
Install the Khronos validation layer and run the game with `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` and a
`vk_layer_settings.txt` enabling `khronos_validation.validate_sync = true` (see `OPTIMISATION_LOG.md`). Voxy's
barriers are validated this way.

# Documents
- `PORTING_PROGRESS.md`: state of the port, what is verified, limitations
- `ARCHITECTURE_NOTES.md`: how the Vulkan backend is integrated into Minecraft (and the original OpenGL inventory it replaced)
- `OPTIMISATION_LOG.md`: everything that was measured and changed for performance
