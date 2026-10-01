package me.cortex.voxy.jmh;

import me.cortex.voxy.common.voxelization.DataLayerLightingSupplier;
import me.cortex.voxy.common.voxelization.ReferenceWorldConversionFactory;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.opt.TestChunks;
import me.cortex.voxy.opt.TestWorlds;
import me.cortex.voxy.opt.reference.ReferenceLighting;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

//World ingest: converting a minecraft chunk section (palette + bit storage + light layers) to voxy voxels
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ConversionJMH {
    private Mapper mapper;
    private TestChunks.Section[] sections;
    private VoxelizedSection out;
    private int i;

    @Setup(Level.Trial)
    public void init() {
        this.mapper = TestWorlds.createMapper();
        var r = new Random(77);
        this.sections = new TestChunks.Section[64];
        for (int j = 0; j < 64; j++) {
            //All light modes interleaved: like real ingest the light supplier call site sees several implementations
            this.sections[j] = TestChunks.create(r, r.nextInt(20) - 2, r.nextInt(40), j % 5 == 0, j % 4);
        }
        this.out = VoxelizedSection.createEmpty();
        //Warm the block state mapping so the benchmark measures conversion, not first time registration
        for (var s : this.sections) {
            WorldConversionFactory.convert(this.out, this.mapper, s.blocks(), s.biomes(), DataLayerLightingSupplier.of(s.blockLight(), s.skyLight()));
        }
    }

    @Benchmark
    public long current() {
        var s = this.sections[(this.i++) & 63];
        return WorldConversionFactory.convert(this.out, this.mapper, s.blocks(), s.biomes(), DataLayerLightingSupplier.of(s.blockLight(), s.skyLight())).section[100];
    }

    @Benchmark
    public long reference() {
        var s = this.sections[(this.i++) & 63];
        return ReferenceWorldConversionFactory.convert(this.out, this.mapper, s.blocks(), s.biomes(), ReferenceLighting.create(s.blockLight(), s.skyLight())).section[100];
    }
}
