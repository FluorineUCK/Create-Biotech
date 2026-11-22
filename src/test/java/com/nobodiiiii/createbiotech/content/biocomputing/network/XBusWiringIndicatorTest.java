package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class XBusWiringIndicatorTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null) type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
            .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
        var registry = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE;
        var read = com.nobodiiiii.createbiotech.registry.CBParticleTypes.BIOCOMPUTING_READ_WAIT;
        if (!registry.containsKey(read.getId())) net.minecraft.core.Registry.register(registry, read.getId(), new SimpleParticleType(false));
        var transfer = com.nobodiiiii.createbiotech.registry.CBParticleTypes.STRAIGHT_ENCHANT;
        if (!registry.containsKey(transfer.getId())) net.minecraft.core.Registry.register(registry, transfer.getId(), new SimpleParticleType(false));
    }
    @Test void physicalContinuityUsesBoundedEndRodDotsAndNeverASuccessfulTransfer() throws Exception {
        var world = world();
        XBusWiringIndicatorBehaviour.render(world, BlockPos.ZERO, new BlockPos(1000, 0, 0),
            new XBusWiringObservation.Result("ready", Optional.of(
                new XBusWiringObservation.Modes(XBusPortState.Mode.IDLE, XBusPortState.Mode.COMPLETE))),
            () -> java.util.stream.IntStream.rangeClosed(0, 1000).mapToObj(x -> new BlockPos(x, 0, 0)).toList());
        assertFalse(world.dots.isEmpty()); assertTrue(world.dots.size() <= 33, "Long lines need bounded cosmetic detail");
        assertTrue(world.dots.stream().allMatch(dot -> dot.particle() == ParticleTypes.END_ROD));
    }
    @Test void pausedPinsHideWaitingAndBrokenBindingNeverDrawsToAnUnavailableTarget() throws Exception {
        var world = world();
        XBusWiringIndicatorBehaviour.render(world, BlockPos.ZERO, new BlockPos(4, 0, 0),
            new XBusWiringObservation.Result("ready", Optional.of(
                new XBusWiringObservation.Modes(XBusPortState.Mode.WRITE, XBusPortState.Mode.PAUSED))),
            XBusWiringIndicatorTest::shortCable);
        assertTrue(world.dots.stream().allMatch(dot -> dot.particle() == ParticleTypes.END_ROD));
        world.dots.clear();
        XBusWiringIndicatorBehaviour.render(world, BlockPos.ZERO, new BlockPos(1000, 0, 0),
            new XBusWiringObservation.Result("target_unloaded", Optional.empty()),
            () -> { throw new AssertionError("An unavailable target must not trigger another world scan"); });
        assertEquals(1, world.dots.size()); assertEquals(new Vec3(.9, 1.1, .9), world.dots.getFirst().position());
        var red = ((DustParticleOptions)world.dots.getFirst().particle()).getColor();
        assertTrue(red.x > red.y && red.x > red.z);
    }
    @Test void waitingModesReuseReadWriteAndReadableMarkersOnlyAtTheirOwnEndpoint() throws Exception {
        var world = world();
        for (var mode : List.of(XBusPortState.Mode.READ, XBusPortState.Mode.WRITE, XBusPortState.Mode.READABLE)) {
            world.dots.clear();
            XBusWiringIndicatorBehaviour.render(world, BlockPos.ZERO, new BlockPos(4, 0, 0),
                new XBusWiringObservation.Result("ready", Optional.of(new XBusWiringObservation.Modes(mode, XBusPortState.Mode.IDLE))),
                XBusWiringIndicatorTest::shortCable);
            var waiting = world.dots.stream().filter(dot -> dot.particle() != ParticleTypes.END_ROD).toList();
            assertEquals(1, waiting.size());
            assertEquals(new Vec3(1.1, 1.25, .5), waiting.getFirst().position());
            assertSame(switch (mode) {
                case READ -> com.nobodiiiii.createbiotech.registry.CBParticleTypes.BIOCOMPUTING_READ_WAIT.get();
                case WRITE -> ParticleTypes.SMALL_FLAME;
                default -> ParticleTypes.COMPOSTER;
            }, waiting.getFirst().particle());
        }
    }
    private static List<BlockPos> shortCable() {
        return java.util.stream.IntStream.rangeClosed(0, 4).mapToObj(x -> new BlockPos(x, 0, 0)).toList();
    }
    @Test void visibleEndRodPointsFollowEachBentCableEdgeIncludingHeightChanges() throws Exception {
        var world = world();
        var path = List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(1, 0, 1),
            new BlockPos(1, 1, 1), new BlockPos(1, 1, 2), new BlockPos(2, 1, 2));
        XBusWiringIndicatorBehaviour.render(world, path.getFirst(), path.getLast(),
            new XBusWiringObservation.Result("ready", Optional.of(new XBusWiringObservation.Modes(
                XBusPortState.Mode.IDLE, XBusPortState.Mode.IDLE))), () -> path);
        assertEquals(11, world.dots.size());
        for (int i = 0; i < world.dots.size(); i++) {
            var dot = world.dots.get(i); assertSame(ParticleTypes.END_ROD, dot.particle());
            int edge = Math.min(path.size() - 2, i / 2);
            var expected = Vec3.atCenterOf(path.get(edge)).lerp(Vec3.atCenterOf(path.get(edge + 1)), i / 2.0 - edge).add(.2, .2, .2);
            assertTrue(expected.distanceTo(dot.position()) < 1e-8, "Particle must stay beside a real cable edge, not a shortcut");
        }
    }
    @Test void missingPhysicalPathNeverFallsBackToAStraightExternalLine() throws Exception {
        var world = world();
        XBusWiringIndicatorBehaviour.render(world, BlockPos.ZERO, new BlockPos(4, 0, 0),
            new XBusWiringObservation.Result("ready", Optional.of(new XBusWiringObservation.Modes(
                XBusPortState.Mode.IDLE, XBusPortState.Mode.IDLE))), List::of);
        assertEquals(1, world.dots.size()); assertTrue(world.dots.getFirst().particle() instanceof DustParticleOptions);
        assertEquals(new Vec3(.9, 1.1, .9), world.dots.getFirst().position());
    }
    @Test void committedExternalTransferUsesGatewayCablePositionsAndKeepsInternalEffect() throws Exception {
        var world = world(); var left = new XBusNetwork.Pin(UUID.randomUUID(), "x0");
        var right = new XBusNetwork.Pin(UUID.randomUUID(), "x1"); var link = new XBusNetwork.Link(left, right);
        var source = new BlockPos(-10, 0, 0); var target = new BlockPos(14, 0, 0);
        var ports = Map.of(left, new XBusConnectedNetwork.Port(source, null), right, new XBusConnectedNetwork.Port(target, null));
        var topology = new XBusNetwork(List.of(left, right), List.of(link));
        var external = new XBusConnectedNetwork.Connected(topology, null, ports,
            List.of(new XBusConnectedNetwork.DisplayWire(link, BlockPos.ZERO, new BlockPos(4, 0, 0), true)));
        XBusWiringIndicatorBehaviour.transferred(world, external, new XBusNetwork.Selection(right, left), (from, to) -> {
            assertEquals(BlockPos.ZERO, from); assertEquals(new BlockPos(4, 0, 0), to); return shortCable();
        });
        assertEquals(9, world.dots.size()); assertTrue(world.dots.stream().allMatch(d -> d.particle() == ParticleTypes.END_ROD));
        world.dots.clear();
        XBusWiringIndicatorBehaviour.transferred(world, external, new XBusNetwork.Selection(left, right), (from, to) -> {
            assertEquals(new BlockPos(4, 0, 0), from); assertEquals(BlockPos.ZERO, to);
            return shortCable().reversed();
        });
        assertEquals(9, world.dots.size()); assertTrue(world.dots.stream().allMatch(d -> d.particle() == ParticleTypes.END_ROD));
        world.dots.clear();
        XBusWiringIndicatorBehaviour.transferred(world, external, new XBusNetwork.Selection(right, left), (from, to) -> List.of());
        assertTrue(world.dots.isEmpty(), "Missing cable geometry must never use the old member-to-member straight effect");
        var internal = new XBusConnectedNetwork.Connected(topology, null, ports,
            List.of(new XBusConnectedNetwork.DisplayWire(link, source, target, false)));
        XBusWiringIndicatorBehaviour.transferred(world, internal, new XBusNetwork.Selection(right, left), (from, to) -> {
            throw new AssertionError("Internal links do not require cables");
        });
        assertEquals(1, world.dots.size());
        assertSame(com.nobodiiiii.createbiotech.registry.CBParticleTypes.STRAIGHT_ENCHANT.get(), world.dots.getFirst().particle());
        assertEquals(Vec3.atCenterOf(source), world.dots.getFirst().position());
        world.particleFailure = true;
        assertDoesNotThrow(() -> XBusWiringIndicatorBehaviour.transferred(world, external,
            new XBusNetwork.Selection(right, left), (from, to) -> shortCable()));
    }
    @Test void displayPathUsesOnlyDeclaredLinksAndHandlesReversedMixedConnections() {
        var a = new XBusNetwork.Pin(UUID.randomUUID(), "x0"); var b = new XBusNetwork.Pin(UUID.randomUUID(), "x0");
        var c = new XBusNetwork.Pin(UUID.randomUUID(), "x0");
        var ab = new XBusConnectedNetwork.DisplayWire(new XBusNetwork.Link(a, b), BlockPos.ZERO, new BlockPos(1, 0, 0), false);
        var cb = new XBusConnectedNetwork.DisplayWire(new XBusNetwork.Link(c, b), new BlockPos(9, 0, 0), new BlockPos(2, 0, 0), true);
        var path = XBusWiringIndicatorBehaviour.displayPath(List.of(ab, cb), a, c);
        assertEquals(2, path.size()); assertEquals(ab, path.getFirst());
        assertEquals(b, path.getLast().pins().left()); assertEquals(c, path.getLast().pins().right());
        assertEquals(new BlockPos(2, 0, 0), path.getLast().left()); assertEquals(new BlockPos(9, 0, 0), path.getLast().right());
        assertTrue(XBusWiringIndicatorBehaviour.displayPath(List.of(ab), a, c).isEmpty());
    }
    @Test void realBehaviourSkipsDisabledDeclarationsThrottlesAndContainsParticleFailure() throws Exception {
        var world = world(); var owner = new Owner(); owner.setLevel(world); world.owner = owner;
        var id = UUID.randomUUID(); var store = new XBusWiringSavedData();
        world.storage = new net.minecraft.world.level.storage.DimensionDataStorage(new java.io.File("unused-in-memory-world"), null, null);
        world.storage.set("create_biotech_xbus_wiring", store);
        var source = new XBusCableTarget(new BlockPos(1, 0, 0), "primary", "source", "x0", id, id);
        var peer = new XBusCableTarget(new BlockPos(4, 0, 0), "primary", "peer", "x1", UUID.randomUUID(), UUID.randomUUID());
        var added = store.add(store.view(id), "wire", source, peer, 8, ignored -> {});
        var diagnostics = new ArrayList<Map<String, String>>();
        var indicator = new XBusWiringIndicatorBehaviour(owner, () -> Optional.of(id), diagnostics::add);
        indicator.tick(); assertTrue(world.dots.isEmpty(), "Disabled declarations do not even resolve their unavailable peers");
        store.setEnabled(added, "wire", true, ignored -> {});
        int interval = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(false);
        world.clock = interval; indicator.tick(); indicator.tick();
        assertEquals(1, world.dots.size(), "Wrong-owner declarations only display one local unavailable marker");
        world.clock++; indicator.tick(); assertEquals(1, world.dots.size());
        world.clock = 2L * interval; indicator.tick(); assertEquals(2, world.dots.size());
        assertEquals(Map.of("", "owner_mismatch"), diagnostics.getLast());
        world.clock = 3L * interval; world.particleFailure = true; assertDoesNotThrow(indicator::tick);
        assertEquals(Map.of("", "owner_mismatch"), diagnostics.getLast(), "Particle failures do not replace real route diagnostics with provider failures");
        var brokenSink = new XBusWiringIndicatorBehaviour(owner, () -> Optional.of(id), ignored -> { throw new IllegalStateException("Cosmetic sink"); });
        assertDoesNotThrow(brokenSink::tick, "A failed diagnostic consumer cannot abort the physical tick");
        world.particleFailure = false; world.owner = null; world.clock = 4L * interval;
        indicator.tick(); assertEquals(2, world.dots.size(), "Removed/replaced physical devices cannot keep displaying wires");
    }
    static World world() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var world = (World)((sun.misc.Unsafe)field.get(null)).allocateInstance(World.class);
        world.dots = new ArrayList<>(); return world;
    }
    record Dot(ParticleOptions particle, Vec3 position) {}
    static final class World extends ServerLevel {
        List<Dot> dots; Owner owner; long clock; boolean particleFailure;
        net.minecraft.world.level.storage.DimensionDataStorage storage;
        private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public boolean hasChunkAt(BlockPos pos) { return true; }
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) { return owner; }
        @Override public net.minecraft.world.level.storage.DimensionDataStorage getDataStorage() { return storage; }
        @Override public long getGameTime() { return clock; }
        @Override public <T extends ParticleOptions> int sendParticles(T particle, double x, double y, double z,
                int count, double dx, double dy, double dz, double speed) {
            if (particleFailure) throw new IllegalStateException("Injected cosmetic failure");
            if (particle == com.nobodiiiii.createbiotech.registry.CBParticleTypes.STRAIGHT_ENCHANT.get()) {
                assertEquals(0, count); assertEquals(1, speed);
            } else {
                assertEquals(1, count, "Static feedback must not use the directed transfer packet");
                assertEquals(0, speed); assertEquals(0, dx); assertEquals(0, dy); assertEquals(0, dz);
            }
            dots.add(new Dot(particle, new Vec3(x, y, z))); return 1;
        }
    }
    static final class Owner extends com.simibubi.create.foundation.blockEntity.SmartBlockEntity {
        static final net.minecraft.world.level.block.entity.BlockEntityType<Owner> TYPE =
            net.minecraft.world.level.block.entity.BlockEntityType.Builder.of((pos, state) -> new Owner(),
                net.minecraft.world.level.block.Blocks.CHEST).build(null);
        Owner() { super(TYPE, BlockPos.ZERO, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()); }
        @Override public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {}
    }
}
