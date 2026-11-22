package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoNetwork;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionDirectory;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Continuous signal publication after Computer execution. This cache never owns world objects or saved power. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class CableSignalWorldTicks {
    private CableSignalWorldTicks() {}
    private static final Map<ServerLevel, CableSignalNetwork.Frame> WORLDS = new WeakHashMap<>();
    public static int power(ServerLevel level, BlockPos pos) {
        return WORLDS.getOrDefault(level, CableSignalNetwork.Frame.EMPTY).outputs().getOrDefault(pos, 0);
    }
    private static final Map<ServerLevel,Set<BlockPos>> CABLES=new WeakHashMap<>();
    private static final Map<ServerLevel,PassiveRedstone.Cache> PASSIVE=new WeakHashMap<>();
    private static void changed(ServerLevel level){var cache=PASSIVE.get(level);if(cache!=null)cache.invalidate();}
    public static void track(ServerLevel level,BlockPos p){if(CABLES.computeIfAbsent(level,k->new HashSet<>()).add(p.immutable()))changed(level);}
    public static void untrack(ServerLevel level,BlockPos p){var set=CABLES.get(level);if(set!=null && set.remove(p))changed(level);}
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void endTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var directory = ComputerExecutionDirectory.snapshot(level);
        var ticking = new HashSet<>(directory.ticking());
        var sources = new ArrayList<CableSignalNetwork.Source>();
        var failures = new HashMap<CableSignalNetwork.Key, String>();
        for (var computer : directory.loaded()) {
            var bindings = computer.signalConnections().bindings();
            if (bindings.values().stream().noneMatch(SignalConnections.Binding::enabled)) continue;
            var signal = ticking.contains(computer) ? computer.signalOutputs() : Optional.<com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoState>empty();
            bindings.forEach((pin, binding) -> {
                if (!binding.enabled()) return;
                var port = !computer.hardware().limits().pins().hasSimpleIo(pin)
                    ? SimpleIoNetwork.Port.unavailable(SimpleIoNetwork.Availability.MISSING)
                    : !ticking.contains(computer) ? SimpleIoNetwork.Port.unavailable(SimpleIoNetwork.Availability.UNLOADED)
                    : signal.map(state -> state.outputs().containsKey(pin) ? SimpleIoNetwork.Port.output(state.outputs().get(pin)) : SimpleIoNetwork.Port.input())
                        .orElseGet(() -> SimpleIoNetwork.Port.unavailable(SimpleIoNetwork.Availability.HARDWARE_MISMATCH));
                var member = computer.blockEntity.getBlockPos();
                try {
                    var route = SignalGatewayAccess.resolve(level, member, pin, binding);
                    sources.add(new CableSignalNetwork.Source(member, pin, route.connector().side(), port,
                        route.connector().position(), route.dependencies()));
                } catch (CableDiscovery.DiscoveryException failure) { failures.put(new CableSignalNetwork.Key(member, pin), failure.code()); }
            });
        }
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos) { return level.isOutsideBuildHeight(pos) || chunk(level, pos) != null; }
            private void check(BlockPos pos) {
                if (!loaded(pos)) throw new CableDiscovery.DiscoveryException("topology_unloaded");
                if (!level.getWorldBorder().isWithinBounds(pos)) throw new CableDiscovery.DiscoveryException("access_denied");
            }
            public CableDiscovery.Cell cell(BlockPos pos) {
                check(pos);
                if (level.isOutsideBuildHeight(pos)) return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null);
                var block = chunk(level, pos).getBlockState(pos).getBlock();
                if (block instanceof DataCableBlock) return DataCableBlock.cell(chunk(level, pos).getBlockState(pos));
                return new CableDiscovery.Cell(block instanceof DataPort ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
            }
            public boolean acceptsCable(BlockPos pos, Direction side) {
                check(pos); if (level.isOutsideBuildHeight(pos)) return false;
                var state = chunk(level, pos).getBlockState(pos);
                return state.getBlock() instanceof DataPort port && port.acceptsCable(state, side);
            }
        };
        var frame = CableSignalNetwork.sample(world, sources, CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded()));
        var inputContacts=new HashMap<BlockPos,Map<String,BlockPos>>();
        for(var source:sources)if("input".equals(frame.status().get(source.key())))
            inputContacts.computeIfAbsent(source.origin(),k->new HashMap<>()).put(source.pin(),source.connector().relative(source.side()));
        directory.loaded().forEach(computer -> {
            var status = new HashMap<String, String>();
            frame.status().forEach((key, value) -> { if (key.origin().equals(computer.blockEntity.getBlockPos())) status.put(key.pin(), value); });
            failures.forEach((key, value) -> { if (key.origin().equals(computer.blockEntity.getBlockPos())) status.put(key.pin(), value); });
            computer.observeSignalConnections(status,inputContacts.getOrDefault(computer.blockEntity.getBlockPos(),Map.of()));
        });
        var outputs=new HashMap<>(frame.outputs());
        outputs.putAll(PASSIVE.computeIfAbsent(level,k->new PassiveRedstone.Cache()).sample(level,CABLES.getOrDefault(level,Set.of()),frame.outputs()));
        publish(level,new CableSignalNetwork.Frame(outputs,frame.status(),frame.dependencies()));
    }
    private static net.minecraft.world.level.chunk.LevelChunk chunk(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }
    private static void publish(ServerLevel level, CableSignalNetwork.Frame next) {
        var old = WORLDS.put(level, next);
        var affected = new HashSet<>(next.outputs().keySet());
        if (old != null) affected.addAll(old.outputs().keySet());
        for (var pos : affected) {
            var chunk = chunk(level, pos); if (chunk == null) continue;
            var state = chunk.getBlockState(pos); if (!(state.getBlock() instanceof DataCableBlock)) continue;
            // A neighbour callback may invalidate this component during publication: never restore a stale frame.
            int power = power(level, pos);
            if (state.getValue(DataCableBlock.POWER) != power)
                level.setBlock(pos, state.setValue(DataCableBlock.POWER, power), Block.UPDATE_ALL);
            else if (old == null || old.outputs().getOrDefault(pos, 0) != power)
                level.updateNeighborsAt(pos, state.getBlock());
        }
    }
    /** A changed cable or source invalidates its entire old component before neighbour redstone queries. */
    public static void invalidate(ServerLevel level, BlockPos pos) {
        invalidate(level, dependency -> dependency.equals(pos));
    }
    @SubscribeEvent public static void shellRemoved(com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellInteractions.Removing event) {
        invalidate(event.level(), event.position());
    }
    private static void invalidate(ServerLevel level, Predicate<BlockPos> changed) {
        var old = WORLDS.get(level); if (old == null) return;
        var failed = new HashSet<BlockPos>();
        for (var dependencies : new HashSet<>(old.dependencies().values()))
            if (dependencies.stream().anyMatch(changed)) failed.addAll(dependencies);
        if (failed.isEmpty()) return;
        var outputs = new HashMap<>(old.outputs()); outputs.keySet().removeAll(failed);
        var dependencies = new HashMap<>(old.dependencies()); dependencies.keySet().removeAll(failed);
        publish(level, new CableSignalNetwork.Frame(outputs, old.status(), dependencies));
    }
    @SubscribeEvent public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pos = event.getChunk().getPos();
            var set=CABLES.get(level);
            if(set!=null && set.removeIf(p->(p.getX()>>4)==pos.x && (p.getZ()>>4)==pos.z))changed(level);
            invalidate(level, dependency -> dependency.getX() >> 4 == pos.x && dependency.getZ() >> 4 == pos.z);
        }
    }
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) return;
        // Chunk restoration does not invoke onPlace. Reconcile saved visual power without trusting it as a source.
        for (int section = 0; section < chunk.getSectionsCount(); section++) {
            var cells = chunk.getSection(section);
            if (!cells.maybeHas(state -> state.getBlock() instanceof DataCableBlock)) continue;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                var state = cells.getBlockState(x, y, z);
                if(state.getBlock() instanceof DataCableBlock)
                    track(level,new BlockPos(chunk.getPos().getMinBlockX()+x,chunk.getSectionYFromSectionIndex(section)*16+y,chunk.getPos().getMinBlockZ()+z));
                if (state.getBlock() instanceof DataCableBlock cable && state.getValue(DataCableBlock.POWER) != 0)
                    level.scheduleTick(new BlockPos(chunk.getPos().getMinBlockX() + x, chunk.getSectionYFromSectionIndex(section) * 16 + y,
                        chunk.getPos().getMinBlockZ() + z), cable, 1);
            }
        }
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { WORLDS.remove(level); CABLES.remove(level); PASSIVE.remove(level); }
    }
}
