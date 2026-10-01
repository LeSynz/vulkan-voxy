package me.cortex.voxy.jmh;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.ReferenceWorldUpdater;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

//World ingest: writing a voxelized 16^3 section (level 0) into its 32^3 world section, same data as WorldUpdaterJMH
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class WorldInsertJMH {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;
    private VoxelizedSection[] vSections;
    private WorldSection[] sections;
    private int i;

    @Setup(Level.Trial)
    public void init() {
        this.sections = new WorldSection[WIDTH*WIDTH*HEIGHT];
        this.vSections = new VoxelizedSection[8000];
        var R = new Random(4326987911L);
        for (int i = 0; i < this.vSections.length; i++) {
            this.vSections[i] = VoxelizedSection.createEmpty().setPosition(R.nextInt(WIDTH*2), R.nextInt(HEIGHT*2), R.nextInt(WIDTH*2));
        }
        for (int i = 0; i < this.sections.length; i++) {
            this.sections[i] = WorldSection._createRawUntrackedUnsafeSection(0, i%WIDTH, i/(WIDTH*WIDTH), (i/WIDTH)%WIDTH);
        }
        for (var vsec : this.vSections) {
            for (int i = 0; i < 30000; i++) {
                vsec.section[R.nextInt(vsec.section.length)] = Mapper.composeMappingId((byte) R.nextInt(), R.nextInt() & 127, 1);
            }
        }
    }

    @Benchmark
    public long current() {
        this.i &= Integer.MAX_VALUE>>1;
        var vsec = this.vSections[(this.i+=999)%this.vSections.length];
        return WorldUpdater.insertSectionLvlIntoWorld(vsec, this.sections[(vsec.x>>>1)+(vsec.z>>>1)*WIDTH+(vsec.y>>>1)*WIDTH*WIDTH]);
    }

    @Benchmark
    public long reference() {
        this.i &= Integer.MAX_VALUE>>1;
        var vsec = this.vSections[(this.i+=999)%this.vSections.length];
        return ReferenceWorldUpdater.insertSectionLvlIntoWorld(vsec, this.sections[(vsec.x>>>1)+(vsec.z>>>1)*WIDTH+(vsec.y>>>1)*WIDTH*WIDTH]);
    }
}
