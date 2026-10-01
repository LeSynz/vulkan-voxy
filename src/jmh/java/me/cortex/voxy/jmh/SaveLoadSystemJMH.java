package me.cortex.voxy.jmh;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.ReferenceSaveLoadSystem3;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.opt.TestSections;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

//Storage: section serialization (before compression) and deserialization (after decompression)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SaveLoadSystemJMH {
    @Param({"terrain", "sparse", "dense300"})
    public String data;

    private WorldSection[] sections;
    private MemoryBuffer[] serialized;
    private WorldSection[] targets;
    private int i;

    @Setup(Level.Trial)
    public void init() {
        var r = new Random(4326987911L);
        this.sections = new WorldSection[32];
        this.serialized = new MemoryBuffer[32];
        this.targets = new WorldSection[32];
        for (int j = 0; j < 32; j++) {
            var s = switch (this.data) {
                case "terrain" -> TestSections.terrain(r, 0, r.nextInt(40) - 4);
                case "sparse" -> TestSections.sparseNoise(r, 0);
                default -> TestSections.denseNoise(r, 0, 300);
            };
            this.sections[j] = s;
            var ser = ReferenceSaveLoadSystem3.serialize(s);
            this.serialized[j] = new MemoryBuffer(ser.size);
            ser.cpyTo(this.serialized[j].address);
            this.targets[j] = WorldSection._createRawUntrackedUnsafeSection(s.lvl, s.x, s.y, s.z);
        }
    }

    @Benchmark
    public long serializeCurrent() {
        return SaveLoadSystem3.serialize(this.sections[(this.i++) & 31]).size;
    }

    @Benchmark
    public long serializeReference() {
        return ReferenceSaveLoadSystem3.serialize(this.sections[(this.i++) & 31]).size;
    }

    @Benchmark
    public boolean deserializeCurrent() {
        int j = (this.i++) & 31;
        return SaveLoadSystem3.deserialize(this.targets[j], this.serialized[j]);
    }

    @Benchmark
    public boolean deserializeReference() {
        int j = (this.i++) & 31;
        return ReferenceSaveLoadSystem3.deserialize(this.targets[j], this.serialized[j]);
    }
}
