package me.cortex.voxy.jmh;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.opt.TestWorlds;
import me.cortex.voxy.opt.reference.ReferenceMipper;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

//LOD building: mipping a 16^3 voxelized section down to 8^3, 4^3, 2^3, 1
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MipperJMH {
    @Param({"terrain", "noise"})
    public String data;

    private Mapper mapper;
    private VoxelizedSection[] sections;
    private int i;

    @Setup(Level.Trial)
    public void init() {
        this.mapper = TestWorlds.createMapper();
        long[] ids = TestWorlds.blockIds(this.mapper);
        var r = new Random(1234);
        this.sections = new VoxelizedSection[64];
        for (int j = 0; j < this.sections.length; j++) {
            this.sections[j] = this.data.equals("terrain") ? TestWorlds.terrainSection(r, ids, r.nextInt(20) - 2) : TestWorlds.noiseSection(r, ids);
        }
    }

    @Benchmark
    public long current() {
        var s = this.sections[(this.i++) & 63];
        WorldVoxilizedSectionMipper.mipSection(s, this.mapper);
        return s.section[s.section.length - 1];
    }

    @Benchmark
    public long reference() {
        var s = this.sections[(this.i++) & 63];
        ReferenceMipper.mipSection(s, this.mapper);
        return s.section[s.section.length - 1];
    }
}
