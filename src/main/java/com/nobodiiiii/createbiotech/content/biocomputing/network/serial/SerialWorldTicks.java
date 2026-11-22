package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionDirectory;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Hardware time runs once after Computer instructions. No catch-up, CPU grants, player or legacy RPC. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class SerialWorldTicks {
    private SerialWorldTicks() {}
    private static final class State {
        long tick = Long.MIN_VALUE;
        final SerialNetwork network = new SerialNetwork();
        final SerialIndicator indicator = new SerialIndicator();
    }
    // Explicit level-unload cleanup: a live component owns hardware that can reference its world.
    private static final Map<ServerLevel, State> WORLDS = new IdentityHashMap<>();
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void endTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var state = WORLDS.computeIfAbsent(level, ignored -> new State());
        long tick = level.getGameTime(); if (state.tick == tick) return;
        state.tick = tick; // A publication failure cannot replay the physical interval.
        var devices = new LinkedHashMap<BlockPos, SerialDevice>();
        var computers = ComputerExecutionDirectory.snapshot(level).ticking();
        computers.forEach(c -> devices.put(c.blockEntity.getBlockPos(), c.serialDevice()));
        var peripherals = SerialPeripheralBehaviour.ticking(level);
        peripherals.forEach(SerialPeripheralBehaviour::processSerialTick);
        peripherals.forEach(peripheral -> devices.put(peripheral.blockEntity.getBlockPos(), peripheral.device()));
        var config = CBConfigs.SERVER.biocomputing; boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
        var media = new EnumMap<SerialWireMedium.Material, SerialWireMedium>(SerialWireMedium.Material.class);
        for (var material : SerialWireMedium.Material.values()) media.put(material, config.serialCable(material, loaded));
        class Wiring implements CableDiscovery.World {
            @Override public Predicate<BlockPos> traversalScope(BlockPos origin) {
                var space = SubLevelCompat.getSpaceId(level, origin);
                return candidate -> SubLevelCompat.matchesSpace(level, candidate, space);
            }
            public boolean loaded(BlockPos pos) { return level.isOutsideBuildHeight(pos) || level.hasChunkAt(pos); }
            private void check(BlockPos pos) {
                if (!loaded(pos)) throw new CableDiscovery.DiscoveryException("topology_unloaded");
                if (!level.getWorldBorder().isWithinBounds(pos)) throw new CableDiscovery.DiscoveryException("access_denied");
            }
            public CableDiscovery.Cell cell(BlockPos pos) {
                check(pos);
                if (level.isOutsideBuildHeight(pos)) return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null);
                var block = level.getBlockState(pos).getBlock();
                if (block instanceof DataCableBlock) return DataCableBlock.cell(level.getBlockState(pos));
                return new CableDiscovery.Cell(block instanceof DataPort ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
            }
            public boolean acceptsCable(BlockPos pos, Direction side) {
                check(pos); if (level.isOutsideBuildHeight(pos)) return false;
                var block = level.getBlockState(pos);
                return block.getBlock() instanceof DataPort port && port.acceptsCable(block, side);
            }
            SerialWireMedium medium(BlockPos pos) {
                check(pos);
                return level.getBlockState(pos).getBlock() instanceof DataCableBlock cable ? media.get(cable.material()) : null;
            }
        }
        var wiring = new Wiring();
        var activity = state.network.tickWired(wiring, devices, config.communication(loaded),
            media.get(SerialWireMedium.Material.BASIC).limits(), wiring::medium,
            (pos, port) -> SerialGatewayAccess.resolve(level, pos, port));
        // Both observers run only after all physical publications, never inside arbitration or queue admission.
        computers.forEach(c -> c.observeSerial());
        state.indicator.accept(level, activity, tick, config.serialIndicatorInterval(loaded));
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) unload(level);
    }
    public static void unload(ServerLevel level) {
        var state = WORLDS.remove(level); if (state != null) state.network.unload();
        SerialPeripheralBehaviour.forget(level);
    }
}
