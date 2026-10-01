package me.cortex.voxy.opt;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

//A grid of sections in an in-memory world, for meshing benchmarks/tests
public final class MeshScenario {
    public final MeshingFixture.Models models;
    public final MeshingFixture.MapStorage storage = new MeshingFixture.MapStorage();
    public final WorldEngine world;
    public final List<long[]> positions = new ArrayList<>();//{lvl, x, y, z} of every stored section

    //kind: "terrain", "noise" (dense random), "sparse" (90% air random)
    public MeshScenario(long seed, String kind, int lvl, int sx, int sy, int sz) {
        var r = new Random(seed);
        this.models = MeshingFixture.createModels(r, 300);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    long[] data = switch (kind) {
                        case "terrain" -> MeshingFixture.terrain(r, this.models, 20 - 32 * y + r.nextInt(6), 18 - 32 * y);
                        case "noise" -> MeshingFixture.noise(r, this.models, 0.3f);
                        default -> MeshingFixture.noise(r, this.models, 0.9f);
                    };
                    this.storage.sections.put(WorldEngine.getWorldSectionId(lvl, x, y, z), data);
                    this.positions.add(new long[]{lvl, x, y, z});
                }
            }
        }
        this.world = MeshingFixture.createWorld(this.storage);
    }

    //Real sections (lod 0 only are meshed/positions) of a game dump
    public MeshScenario(RealData real) {
        this.models = MeshingFixture.createModels(real);
        real.sections.forEach(this.storage.sections::put);
        for (var p : real.positions) {
            if (p[0] == 0) this.positions.add(p);
        }
        this.world = MeshingFixture.createWorld(this.storage);
    }

    public WorldSection acquire(int i) {
        var p = this.positions.get(i);
        return this.world.acquire((int) p[0], (int) p[1], (int) p[2], (int) p[3]);
    }
}
