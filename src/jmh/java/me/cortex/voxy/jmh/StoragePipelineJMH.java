package me.cortex.voxy.jmh;

import me.cortex.voxy.common.config.compressors.LZ4Compressor;
import me.cortex.voxy.common.config.compressors.StorageCompressor;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.ReferenceSaveLoadSystem3;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.opt.TestSections;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

//Storage: the full section save/load path (serialize + compress, decompress + deserialize) with the
// default compressor (ZSTD level 1, see StorageConfigUtil) and LZ4. Puts the serializer change in context
// of the compression cost; the compressors themselves are not modified.
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class StoragePipelineJMH {
    @Param({"terrain", "dense300"})
    public String data;

    @Param({"zstd1", "lz4"})
    public String compressor;

    private StorageCompressor comp;
    private WorldSection[] sections;
    private MemoryBuffer[] serialized;
    private MemoryBuffer[] compressed;
    private WorldSection[] targets;
    private int i;

    @Setup(Level.Trial)
    public void init() {
        this.comp = this.compressor.equals("lz4") ? new LZ4Compressor() : new ZSTDCompressor(1);
        var r = new Random(4326987911L);
        this.sections = new WorldSection[32];
        this.serialized = new MemoryBuffer[32];
        this.compressed = new MemoryBuffer[32];
        this.targets = new WorldSection[32];
        long rawTotal = 0, compTotal = 0;
        for (int j = 0; j < 32; j++) {
            var s = this.data.equals("terrain") ? TestSections.terrain(r, 0, r.nextInt(40) - 4) : TestSections.denseNoise(r, 0, 300);
            this.sections[j] = s;
            var ser = ReferenceSaveLoadSystem3.serialize(s);
            this.serialized[j] = new MemoryBuffer(ser.size);
            ser.cpyTo(this.serialized[j].address);
            var c = this.comp.compress(this.serialized[j]);
            this.compressed[j] = new MemoryBuffer(c.size);
            c.cpyTo(this.compressed[j].address);
            rawTotal += ser.size;
            compTotal += c.size;
            this.targets[j] = WorldSection._createRawUntrackedUnsafeSection(s.lvl, s.x, s.y, s.z);
        }
        System.out.println("\n[" + this.data + "/" + this.compressor + "] avg serialized " + rawTotal / 32 + " B, avg compressed " + compTotal / 32 + " B");
    }

    @Benchmark
    public long compressOnly() {
        return this.comp.compress(this.serialized[(this.i++) & 31]).size;
    }

    @Benchmark
    public long decompressOnly() {
        return this.comp.decompress(this.compressed[(this.i++) & 31]).size;
    }

    @Benchmark
    public long saveCurrent() {
        return this.comp.compress(SaveLoadSystem3.serialize(this.sections[(this.i++) & 31])).size;
    }

    @Benchmark
    public long saveReference() {
        return this.comp.compress(ReferenceSaveLoadSystem3.serialize(this.sections[(this.i++) & 31])).size;
    }

    @Benchmark
    public boolean loadCurrent() {
        int j = (this.i++) & 31;
        return SaveLoadSystem3.deserialize(this.targets[j], this.comp.decompress(this.compressed[j]));
    }
}
