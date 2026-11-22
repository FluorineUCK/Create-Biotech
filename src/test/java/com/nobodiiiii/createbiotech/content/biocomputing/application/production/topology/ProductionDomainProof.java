package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Internal, read-only production transaction evidence. Never a programmable value or a world-access grant. */
public final class ProductionDomainProof {
    private ProductionDomainProof() {}
    public enum Failure { NONE, PARTIAL, SPACE }

    /** H is an exact loaded-instance witness; S is its immutable captured production state. */
    public interface World<H,S> {
        boolean isChunkLoaded(ChunkPos chunk);
        boolean isLoaded(BlockPos pos);
        boolean sameSpace(BlockPos seed, BlockPos pos);
        Address address(BlockPos pos);
        @Nullable H loadedComputer(BlockPos pos);
        S state(H computer);
        boolean matches(H computer, S expected);
    }
    public record Domain(List<Bounds> bounds, List<Address> anchors) {
        public Domain {
            bounds=List.copyOf(bounds); anchors=List.copyOf(anchors);
            if (bounds.isEmpty()) throw new IllegalArgumentException("empty production proof domain");
        }
    }
    public record Cell<H,S>(BlockPos pos, @Nullable H computer, @Nullable S state) {
        public Cell {
            pos=Objects.requireNonNull(pos).immutable();
            if ((computer==null)!=(state==null)) throw new IllegalArgumentException("incomplete instance witness");
        }
    }
    public record Capture<H,S>(Domain domain, Failure failure, List<Cell<H,S>> cells) {
        public Capture { Objects.requireNonNull(domain); Objects.requireNonNull(failure); cells=List.copyOf(cells); }
    }

    public static <H,S> Failure prove(BlockPos seed, Domain domain, World<H,S> world) {
        Objects.requireNonNull(world); seed=Objects.requireNonNull(seed).immutable();
        var chunks=new LinkedHashSet<ChunkPos>();
        for (var bounds : domain.bounds()) chunks.addAll(ProductionStructure.chunksFor(bounds));
        for (var anchor : domain.anchors()) chunks.add(new ChunkPos(anchor.localPos()));
        for (var chunk : chunks) if (!world.isChunkLoaded(chunk)) return Failure.PARTIAL;
        for (var pos : positions(domain)) {
            if (!world.isLoaded(pos)) return Failure.PARTIAL;
            if (!world.sameSpace(seed,pos)) return Failure.SPACE;
        }
        for (var anchor : domain.anchors())
            if (!anchor.sameSpace(world.address(anchor.localPos()))) return Failure.SPACE;
        return Failure.NONE;
    }

    public static <H,S> Capture<H,S> capture(BlockPos seed, Domain domain, World<H,S> world) {
        var failure=prove(seed,domain,world);
        if (failure!=Failure.NONE) return new Capture<>(domain,failure,List.of());
        var cells=new ArrayList<Cell<H,S>>();
        for (var pos : positions(domain)) {
            var computer=world.loadedComputer(pos);
            cells.add(new Cell<>(pos,computer,computer==null ? null : world.state(computer)));
        }
        return new Capture<>(domain,Failure.NONE,cells);
    }

    public static <H,S> boolean revalidate(Capture<H,S> original, BlockPos seed, Domain domain, World<H,S> world) {
        if (original.failure()!=Failure.NONE || !original.domain().equals(domain)) return false;
        var now=capture(seed,domain,world);
        if (now.failure()!=Failure.NONE || now.cells().size()!=original.cells().size()) return false;
        for (int i=0;i<original.cells().size();i++) {
            var before=original.cells().get(i); var after=now.cells().get(i);
            if (!before.pos().equals(after.pos()) || before.computer()!=after.computer()) return false;
            if (before.computer()!=null && !world.matches(before.computer(),before.state())) return false;
        }
        return true;
    }

    /** Bounded offsets never increment an absolute int coordinate beyond its maximum. */
    public static List<BlockPos> positions(Domain domain) {
        var positions=new LinkedHashSet<BlockPos>();
        for (var b : domain.bounds()) {
            int width=b.maxX()-b.minX()+1, height=b.maxY()-b.minY()+1, depth=b.maxZ()-b.minZ()+1;
            for (int x=0;x<width;x++) for (int y=0;y<height;y++) for (int z=0;z<depth;z++)
                positions.add(new BlockPos(b.minX()+x,b.minY()+y,b.minZ()+z));
        }
        for (var anchor : domain.anchors()) positions.add(anchor.localPos());
        return List.copyOf(positions);
    }
}
