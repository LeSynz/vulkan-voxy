package me.cortex.voxy.jmh;

import me.cortex.voxy.client.core.rendering.building.ReferenceRenderDataFactory;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.opt.MeshScenario;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

//Meshing: RenderDataFactory.generateMesh for one 32^3 section (incl. neighbour face lookups), synthetic models
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MeshingJMH {
    @Param({"terrain", "noise", "sparse"})
    public String data;

    private MeshScenario scenario;
    private WorldSection[] sections;
    private RenderDataFactory current;
    private ReferenceRenderDataFactory reference;
    private int i;

    @Setup(Level.Trial)
    public void init() {
        this.scenario = new MeshScenario(1234, this.data, 0, 4, 2, 4);
        this.sections = new WorldSection[this.scenario.positions.size()];
        for (int j = 0; j < this.sections.length; j++) {
            this.sections[j] = this.scenario.acquire(j);//Keep acquired for the whole run
        }
        this.current = new RenderDataFactory(this.scenario.world, this.scenario.models.factory, false);
        this.reference = new ReferenceRenderDataFactory(this.scenario.world, this.scenario.models.factory, false);
    }

    @Benchmark
    public long meshCurrent() {
        var s = this.current.generateMesh(this.sections[(this.i++) & 31]);
        long r = s.aabb;
        s.free();
        return r;
    }

    @Benchmark
    public long meshReference() {
        var s = this.reference.generateMesh(this.sections[(this.i++) & 31]);
        long r = s.aabb;
        s.free();
        return r;
    }
}
