package me.cortex.voxy.jmh;

import me.cortex.voxy.client.core.rendering.building.ReferenceRenderDataFactory;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.opt.MeshScenario;
import me.cortex.voxy.opt.RealData;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

//Meshing of real world sections (VOXY_REAL_DATA, see RealData), one section per op
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class RealMeshingJMH {
    private MeshScenario scenario;
    private WorldSection[] sections;
    private RenderDataFactory current;
    private ReferenceRenderDataFactory reference;
    private int i;

    @Setup(Level.Trial)
    public void init() {
        var real = RealData.loadOrNull();
        if (real == null) throw new IllegalStateException("VOXY_REAL_DATA is not set");
        this.scenario = new MeshScenario(real);
        int n = Math.min(256, this.scenario.positions.size());
        this.sections = new WorldSection[n];
        for (int j = 0; j < n; j++) this.sections[j] = this.scenario.acquire(j);
        this.current = new RenderDataFactory(this.scenario.world, this.scenario.models.factory, false);
        this.reference = new ReferenceRenderDataFactory(this.scenario.world, this.scenario.models.factory, false);
    }

    @Benchmark
    public long meshCurrent() {
        var s = this.current.generateMesh(this.sections[(this.i++) % this.sections.length]);
        long r = s.aabb;
        s.free();
        return r;
    }

    @Benchmark
    public long meshReference() {
        var s = this.reference.generateMesh(this.sections[(this.i++) % this.sections.length]);
        long r = s.aabb;
        s.free();
        return r;
    }
}
