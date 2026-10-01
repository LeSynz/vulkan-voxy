package me.cortex.voxy.opt;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

//Minecraft registry bootstrap for CPU-only tests/benchmarks that need block states (no game, no gpu)
public final class McBootstrap {
    private static boolean done;

    public static synchronized void init() {
        if (!done) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            done = true;
        }
    }

    private McBootstrap() {}
}
