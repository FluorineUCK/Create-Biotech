package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPackagingBackend.Route;
import net.minecraft.resources.ResourceLocation;
import java.util.concurrent.atomic.AtomicInteger;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import static org.junit.jupiter.api.Assertions.*;

class StockMaterialGrantTest {
    @Test void exactRouteAndCapturedSourceTargetSurviveStrictRoundTrip() {
        var grantor = new GameProfile(UUID.randomUUID(), "grantor");
        var source = new SerialControlAccess.Binding(UUID.randomUUID(), BlockPos.ZERO, UUID.randomUUID(), "s0",
            Direction.EAST, Optional.empty());
        var target = new StockLinkTarget(new BlockPos(4, 5, 6), UUID.randomUUID(), UUID.randomUUID());
        var grant = new StockMaterialGrant(42, StockMaterialGrant.route("  workshop east  "), grantor,
            source, UUID.randomUUID(), target);
        assertEquals("workshop east", grant.address());
        assertEquals("中转站 東", StockMaterialGrant.route(" 中转站 東 "));
        assertEquals(grant, StockMaterialGrant.load(grant.save()));
        var corrupt = grant.save();
        corrupt.remove("Installation");
        assertThrows(IllegalArgumentException.class, () -> StockMaterialGrant.load(corrupt));
        for (String raw : List.of("", "*", "work*", "work?", "Dock [12]", "Workshop {East,West}", "Dock {^A}", "Dock \\West", "a" + (char)0 + "b", "x".repeat(26)))
            assertThrows(IllegalArgumentException.class, () -> StockMaterialGrant.route(raw));
    }

    @Test void restoredGlobGrantIsRejectedAndPhysicalIdentityDoesNotDependOnBlockNbt() {
        var grantor = new GameProfile(UUID.randomUUID(), "grantor");
        var source = new SerialControlAccess.Binding(UUID.randomUUID(), BlockPos.ZERO, UUID.randomUUID(), "s0",
            Direction.EAST, Optional.empty());
        var target = new StockLinkTarget(new BlockPos(4, 5, 6), UUID.randomUUID(), UUID.randomUUID());
        var raw = new StockMaterialGrant(42, "Dock East", grantor, source, UUID.randomUUID(), target).save();
        raw.putString("Address", "Dock [12]");
        assertThrows(IllegalArgumentException.class, () -> StockMaterialGrant.load(raw));
        var overworld = ResourceLocation.parse("minecraft:overworld");
        var id = StockMaterialOutput.deviceAt(overworld, target.position());
        assertEquals(id, StockMaterialOutput.deviceAt(overworld, target.position()));
        assertNotEquals(id, StockMaterialOutput.deviceAt(overworld, target.position().east()));
        assertNotEquals(id, StockMaterialOutput.deviceAt(ResourceLocation.parse("minecraft:the_nether"), target.position()));
    }

    @Test void deniedBacklogDoesNotReadTheJournalAndForeignScopeOnlyProvesOnce() {
        var source = UUID.randomUUID(); var oldNetwork = UUID.randomUUID();
        var manifest = new DataValue.RecordValue(Map.of("network", new DataValue.TextValue(oldNetwork.toString())));
        var retained = new LinkedHashMap<Key,Order>();
        for (int i=0; i<128; i++) {
            var key = new Key(source, UUID.randomUUID());
            retained.put(key, new Order(key, manifest, new Plan("create.packaging",1,List.of(manifest)),0,
                Phase.QUEUED,Optional.empty(),DataValue.NullValue.INSTANCE,"queued"));
        }
        var reads = new AtomicInteger(); var proofs = new AtomicInteger();
        var authority = new StockMaterialAuthority();
        Journal journal = new Journal() {
            public Snapshot load() { reads.incrementAndGet(); return new Snapshot(0,0,retained); }
            public boolean commit(long revision, Snapshot next) { throw new AssertionError("foreign order mutated"); }
        };
        Backend backend = new Backend() {
            public boolean authorized(UUID id) { return authority.route(id).isPresent(); }
            public boolean supports(String extension, int version) { return true; }
            public boolean inScope(Order order) { return authority.inScope(order); }
            public Preparation prepare(UUID id, DataValue.RecordValue data) { throw new AssertionError(); }
            public Dispatch dispatch(Operation op, DataValue.RecordValue command) { throw new AssertionError("foreign effect"); }
            public Observation observe(Operation op, DataValue.RecordValue receipt) { throw new AssertionError(); }
        };
        var service = new ProductionOrderService(journal,backend,new Limits(128,1,8192));
        assertNull(authority.with(source, () -> { proofs.incrementAndGet(); return Optional.empty(); }, service::advance, null));
        assertEquals(1,proofs.get()); assertEquals(0,reads.get());
        assertEquals("idle", authority.with(source, () -> {
            proofs.incrementAndGet(); return Optional.of(new Route(UUID.randomUUID(),"Dock",1));
        }, service::advance, null).code());
        assertEquals(2,proofs.get()); assertEquals(1,reads.get());
        assertTrue(authority.route(source).isEmpty(), "Witness ends with the invocation");
    }

    @Test void authorityOverReplacementNetworkCannotUseOrAdvanceOldOrder() {
        class World implements Backend, Journal {
            Snapshot snapshot = Snapshot.empty();
            boolean oldNetwork = true;
            int effects;
            public Snapshot load() { return snapshot; }
            public boolean commit(long expected, Snapshot next) { snapshot = next; return true; }
            public boolean authorized(UUID source) { return true; }
            public boolean supports(String extension, int version) { return true; }
            public boolean inScope(Order order) { return oldNetwork; }
            public Preparation prepare(UUID source, DataValue.RecordValue request) {
                return Preparation.ready(new Plan("create.packaging", 1, List.of(request)));
            }
            public Dispatch dispatch(Operation operation, DataValue.RecordValue command) {
                effects++;
                return Dispatch.accepted(new DataValue.RecordValue(Map.of()));
            }
            public Observation observe(Operation operation, DataValue.RecordValue receipt) { return Observation.pending(); }
        }
        var world = new World();
        var service = new ProductionOrderService(world, world, new Limits(4, 1, 2048));
        var key = new Key(UUID.randomUUID(), UUID.randomUUID());
        var manifest = new DataValue.RecordValue(Map.of());
        assertEquals("accepted", service.submit(key, manifest).code());
        var accepted = world.snapshot;
        world.oldNetwork = false;
        assertEquals("denied", service.status(key).code());
        assertEquals("denied", service.submit(key, manifest).code());
        assertEquals("idle", service.advance().code());
        assertEquals(accepted, world.snapshot);
        assertEquals(0, world.effects);
        world.oldNetwork = true;
        assertEquals("existing", service.submit(key, manifest).code());
        assertEquals("progressed", service.advance().code());
        assertEquals(1, world.effects);
    }
}
