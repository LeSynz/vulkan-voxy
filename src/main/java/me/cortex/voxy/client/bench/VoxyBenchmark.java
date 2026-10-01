package me.cortex.voxy.client.bench;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.vulkan.VkBarriers;
import me.cortex.voxy.client.core.vulkan.VkGpuTimer;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.GameType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

//Scripted in game benchmark, enabled with -Dvoxy.bench=true (run with --quickPlaySingleplayer <world>).
// Joins the world, lets it settle (loading metrics), then flies the camera along a circle recording every frame time.
// Results are written to -Dvoxy.bench.out (json) and the game is closed. Not active unless the property is set.
public final class VoxyBenchmark {
    public static final boolean ENABLED = Boolean.getBoolean("voxy.bench");
    private static final double SETTLE_S = Double.parseDouble(System.getProperty("voxy.bench.settle", "45"));
    private static final double DURATION_S = Double.parseDouble(System.getProperty("voxy.bench.duration", "90"));
    private static final double RADIUS = Double.parseDouble(System.getProperty("voxy.bench.radius", "700"));
    private static final double SPEED = Double.parseDouble(System.getProperty("voxy.bench.speed", "80"));//blocks per second
    private static final double HEIGHT = Double.parseDouble(System.getProperty("voxy.bench.height", "130"));
    private static final String OUT = System.getProperty("voxy.bench.out", "voxy-bench.json");
    private static final String LABEL = System.getProperty("voxy.bench.label", "run");

    private static final String WORLD = System.getProperty("voxy.bench.world", "bench-world");
    private static final boolean POSES = !"false".equals(System.getProperty("voxy.bench.poses", "true"));
    private static final double POSE_WAIT_S = Double.parseDouble(System.getProperty("voxy.bench.poseWait", "8"));
    private static int poseIndex;
    private static boolean poseShot;
    private static boolean joinRequested;
    private static int waitFrames;
    private static long lastScreenLogNs;

    private enum Phase {WAIT_WORLD, SETTLE, POSES, RUN, DONE}

    private static Phase phase = Phase.WAIT_WORLD;
    private static long startNs, phaseStartNs, lastFrameNs, lastSampleNs;
    private static double cx, cz;
    private static double[] frames = new double[1 << 20];
    private static int frameCount;
    private static final List<String> samples = new ArrayList<>();
    private static double timeToRenderSystem = -1;
    private static double timeToFirstSection = -1, timeTo90Sections = -1;
    private static final List<double[]> sectionSeries = new ArrayList<>();//{t, sectionCount}
    private static double timeToQuiet = -1;
    private static int quietStreak;
    private static String lastDebugKey = "";

    //Called at the start of every frame on the render thread
    public static void onFrame(Minecraft mc) {
        long now = System.nanoTime();
        switch (phase) {
            case WAIT_WORLD -> {
                //Join the world ourselves (--quickPlaySingleplayer waits on online services that do not answer in a dev environment)
                if (mc.level == null) {
                    waitFrames++;
                    if (!joinRequested && mc.gui.screen() instanceof TitleScreen && waitFrames > 60) {
                        joinRequested = true;
                        Logger.info("[bench] opening world " + WORLD);
                        mc.createWorldOpenFlows().openWorld(WORLD, () -> Logger.error("[bench] world open was cancelled"));
                    }
                    //"world needs a backup / was last played in another version" style confirmations: proceed without a backup
                    if (mc.gui.screen() instanceof BackupConfirmScreen confirm && waitFrames > 90) {
                        try {
                            var f = BackupConfirmScreen.class.getDeclaredField("onProceed");
                            f.setAccessible(true);
                            ((BackupConfirmScreen.Listener) f.get(confirm)).proceed(false, false);
                            Logger.info("[bench] confirmed " + confirm.getClass().getSimpleName());
                        } catch (ReflectiveOperationException e) {
                            Logger.error("[bench] could not confirm screen", e);
                        }
                    }
                    if (now - lastScreenLogNs > 10_000_000_000L) {
                        lastScreenLogNs = now;
                        Logger.info("[bench] waiting for world, screen=" + (mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getSimpleName()));
                    }
                }
                if (mc.player != null && mc.level != null && mc.getSingleplayerServer() != null) {
                    phase = Phase.SETTLE;
                    startNs = phaseStartNs = lastSampleNs = now;
                    cx = mc.player.getX();
                    cz = mc.player.getZ();
                    var server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayers().get(0);
                        sp.setGameMode(GameType.SPECTATOR);
                    });
                    Logger.info("[bench] joined world, settling for " + SETTLE_S + "s");
                }
            }
            case SETTLE -> {
                sample(mc, now);
                if ((now - phaseStartNs) / 1e9 >= SETTLE_S) {
                    if (!sectionSeries.isEmpty()) {
                        double fin = sectionSeries.get(sectionSeries.size() - 1)[1];
                        for (var e : sectionSeries) {
                            if (e[1] >= fin * 0.9) { timeTo90Sections = e[0]; break; }
                        }
                    }
                    phase = POSES ? Phase.POSES : Phase.RUN;
                    phaseStartNs = now;
                    lastFrameNs = 0;
                    if (phase == Phase.RUN) {
                        VkGpuTimer.reset();
                        VkBarriers.resetStats();
                        Logger.info("[bench] starting flight");
                    } else {
                        Logger.info("[bench] capturing poses");
                        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
                        fixWorldState(mc);
                    }
                }
                place(mc, 0);
            }
            case POSES -> {
                //Stationary screenshots at fixed poses for image comparison between builds
                double t = (now - phaseStartNs) / 1e9;
                sample(mc, now);
                final double[][] poses = {{0, 15}, {90, 10}, {180, 20}, {270, 5}};//yaw, pitch
                var server = mc.getSingleplayerServer();
                if (poseIndex < poses.length && server != null) {
                    double yaw = poses[poseIndex][0], pitch = poses[poseIndex][1];
                    server.execute(() -> {
                        var players = server.getPlayerList().getPlayers();
                        if (players.isEmpty()) return;
                        var sp = players.get(0);
                        sp.teleportTo(sp.level(), cx, HEIGHT, cz, Set.of(), (float) yaw, (float) pitch, false);
                    });
                    if (!poseShot && t >= POSE_WAIT_S) {
                        poseShot = true;
                        Screenshot.grab(mc.gameDirectory, "bench-" + LABEL + "-pose" + poseIndex + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> {});
                    } else if (poseShot && t >= POSE_WAIT_S + 1.5) {
                        poseIndex++;
                        poseShot = false;
                        phaseStartNs = now;
                    }
                } else {
                    if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
                    phase = Phase.RUN;
                    phaseStartNs = now;
                    lastFrameNs = 0;
                    VkGpuTimer.reset();
                    VkBarriers.resetStats();
                    Logger.info("[bench] starting flight");
                }
            }
            case RUN -> {
                double t = (now - phaseStartNs) / 1e9;
                if (lastFrameNs != 0 && frameCount < frames.length) {
                    frames[frameCount++] = (now - lastFrameNs) / 1e6;
                }
                lastFrameNs = now;
                sample(mc, now);
                place(mc, t);
                if (t >= DURATION_S) {
                    phase = Phase.DONE;
                    finish(mc);
                }
            }
            case DONE -> {}
        }
    }

    //Freeze the time of day / weather so screenshots of different builds are comparable
    private static void fixWorldState(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            var src = server.createCommandSourceStack();
            for (String cmd : new String[]{"gamerule advance_time false", "gamerule advance_weather false", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "time set 6000", "weather clear 100000"}) {
                try {
                    server.getCommands().performPrefixedCommand(src, cmd);
                } catch (Throwable t) {
                    Logger.warn("[bench] command failed: " + cmd);
                }
            }
        });
    }

    private static void place(Minecraft mc, double t) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        double ang = (SPEED * t) / RADIUS;
        double x = cx + Math.cos(ang) * RADIUS - RADIUS;
        double z = cz + Math.sin(ang) * RADIUS;
        float yaw = (float) Math.toDegrees(ang) + 180f;//look along the direction of travel (+ a slow sweep in the pitch)
        float pitch = (float) (10 + 10 * Math.sin(t * 0.5));
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            var sp = players.get(0);
            sp.teleportTo(sp.level(), x, HEIGHT, z, Set.of(), yaw, pitch, false);
        });
    }

    //Once per second: voxy's debug lines (queue sizes, section counts) and the loading metrics
    private static void sample(Minecraft mc, long now) {
        //Fine grained (100ms) while the world is loading in, 1s afterwards
        double sinceJoin = (now - startNs) / 1e9;
        long interval = sinceJoin < 20 ? 100_000_000L : 1_000_000_000L;
        if (now - lastSampleNs < interval) return;
        lastSampleNs = now;
        var holder = IVoxyRenderSystemHolder.getNullable();
        double t = (now - startNs) / 1e9;
        if (holder == null) return;
        if (timeToRenderSystem < 0) timeToRenderSystem = t;
        var lines = new ArrayList<String>();
        holder.addDebugInfo(lines);
        var joined = String.join(" | ", lines);
        var m = java.util.regex.Pattern.compile("#N: \\d+/\\d+,(\\d+)").matcher(joined);
        if (m.find()) {
            int count = Integer.parseInt(m.group(1));
            sectionSeries.add(new double[]{t, count});
            if (count > 0 && timeToFirstSection < 0) timeToFirstSection = t;
        }
        if (sinceJoin >= 1.0 || sinceJoin < 20) {
            if (interval == 1_000_000_000L || (int) (t * 10) % 10 == 0) samples.add(String.format("%.1f %s", t, joined));
        }
        //Loading complete: mesh queue empty and nothing changed for 3 samples
        String queue = lines.stream().filter(l -> l.startsWith("RSSQ")).findFirst().orElse("");
        String key = queue + lines.stream().filter(l -> l.startsWith("UC/GC")).findFirst().orElse("");
        if (queue.startsWith("RSSQ/TFC: 0/") && key.equals(lastDebugKey)) {
            if (++quietStreak == 3 && timeToQuiet < 0) timeToQuiet = t - 3;
        } else {
            quietStreak = 0;
        }
        lastDebugKey = key;
    }

    private static void finish(Minecraft mc) {
        double[] f = Arrays.copyOf(frames, frameCount);
        double[] sorted = f.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (double d : f) sum += d;
        double avg = sum / Math.max(1, f.length);
        double median = pct(sorted, 0.5);
        int over2x = 0, over33 = 0, over50 = 0;
        for (double d : f) {
            if (d > median * 2) over2x++;
            if (d > 33.3) over33++;
            if (d > 50) over50++;
        }
        //1% low / 0.1% low as average fps of the slowest 1% / 0.1% of frames
        double low1 = avgTop(sorted, 0.01), low01 = avgTop(sorted, 0.001);
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"label\": \"").append(LABEL).append("\",\n");
        sb.append(String.format("  \"frames\": %d,\n", f.length));
        sb.append(String.format("  \"avg_fps\": %.2f,\n", 1000.0 / avg));
        sb.append(String.format("  \"avg_ms\": %.3f,\n", avg));
        sb.append(String.format("  \"median_ms\": %.3f,\n", median));
        sb.append(String.format("  \"p95_ms\": %.3f,\n  \"p99_ms\": %.3f,\n  \"p999_ms\": %.3f,\n  \"max_ms\": %.3f,\n", pct(sorted, 0.95), pct(sorted, 0.99), pct(sorted, 0.999), sorted.length == 0 ? 0 : sorted[sorted.length - 1]));
        sb.append(String.format("  \"fps_1pct_low\": %.2f,\n  \"fps_01pct_low\": %.2f,\n", 1000.0 / low1, 1000.0 / low01));
        sb.append(String.format("  \"frames_over_2x_median\": %d,\n  \"frames_over_33ms\": %d,\n  \"frames_over_50ms\": %d,\n", over2x, over33, over50));
        sb.append(String.format("  \"time_to_voxy_render_system_s\": %.1f,\n  \"time_to_quiet_loading_s\": %.1f,\n", timeToRenderSystem, timeToQuiet));
        sb.append(String.format("  \"time_to_first_section_s\": %.2f,\n  \"time_to_90pct_sections_s\": %.2f,\n", timeToFirstSection, timeTo90Sections));
        sb.append(String.format("  \"voxy_barriers_per_frame\": %.1f,\n  \"voxy_render_passes_per_frame\": %.1f,\n", VkBarriers.averagePerFrame(), VkBarriers.passesPerFrame()));
        sb.append("  \"voxy_barrier_types_per_frame\": {");
        for (int i = 0; i < VkBarriers.TYPES.length; i++) {
            sb.append(String.format("%s\"%s\": %.1f", i == 0 ? "" : ", ", VkBarriers.TYPES[i], VkBarriers.barrierFrames == 0 ? 0 : (double) VkBarriers.typeTotals[i] / VkBarriers.barrierFrames));
        }
        sb.append("},\n");
        sb.append("  \"voxy_gpu_ms\": {");
        var gpu = VkGpuTimer.averages();
        int gi = 0;
        for (var e : gpu.entrySet()) {
            sb.append(String.format("%s\"%s\": %.4f", gi++ == 0 ? "" : ", ", e.getKey(), e.getValue()));
        }
        sb.append("},\n");
        sb.append("  \"samples\": [\n");
        for (int i = 0; i < samples.size(); i++) {
            sb.append("    \"").append(samples.get(i).replace("\"", "'")).append("\"").append(i + 1 < samples.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        try {
            Files.writeString(Path.of(OUT), sb.toString());
            var csv = new StringBuilder();
            for (double d : f) csv.append(String.format("%.4f\n", d));
            Files.writeString(Path.of(OUT + ".frames.csv"), csv.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        Logger.info("[bench] done: " + String.format("%.1f avg fps, 1%% low %.1f, %d frames", 1000.0 / avg, 1000.0 / low1, f.length));
        mc.stop();
        var killer = new Thread(() -> {
            try {Thread.sleep(20_000);} catch (InterruptedException ignored) {}
            Runtime.getRuntime().halt(0);
        });
        killer.setDaemon(true);
        killer.start();
    }

    private static double pct(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))];
    }

    private static double avgTop(double[] sorted, double fraction) {
        if (sorted.length == 0) return 1;
        int n = Math.max(1, (int) (sorted.length * fraction));
        double s = 0;
        for (int i = sorted.length - n; i < sorted.length; i++) s += sorted[i];
        return s / n;
    }

    private VoxyBenchmark() {}
}
