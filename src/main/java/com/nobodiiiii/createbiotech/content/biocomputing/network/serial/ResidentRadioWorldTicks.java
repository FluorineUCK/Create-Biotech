package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import net.minecraft.core.Direction;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import java.util.Locale;
import java.util.Optional;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionDirectory;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Once-per-world physical radio, independent of wired discovery and of the production router. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class ResidentRadioWorldTicks {
    private static final Map<ServerLevel, WorldRadio> WORLDS = new IdentityHashMap<>();
    private static final class WorldRadio {
        ResidentRadioHardware profile;
        RadioSerialNetwork domain;
        long tick = Long.MIN_VALUE;
    }
    private ResidentRadioWorldTicks() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void endTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        var seen = ComputerExecutionDirectory.snapshot(level);
        var state = WORLDS.computeIfAbsent(level, ignored -> new WorldRadio());
        long time = seen.tick();
        if (state.tick == time) return;
        state.tick = time;

        var config = CBConfigs.SERVER.biocomputing;
        boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
        ResidentRadioHardware profile;
        try { profile = config.residentRadio(loaded); }
        catch (IllegalArgumentException invalid) {
            if (state.domain != null) state.domain.unload();
            state.domain = null; state.profile = null;
            return;
        }
        if (!profile.equals(state.profile)) {
            if (state.domain != null) state.domain.unload();
            state.domain = profile.newDomain(); state.profile = profile;
        }
        var crystals = new HashMap<BlockPos, CarrierFrequency>();
        var rxFaces = new HashMap<BlockPos, Direction>();
        var stages = new LinkedHashMap<BlockPos, ComputerBehaviour>();
        for (var computer : seen.ticking()) {
            var pos = computer.blockEntity.getBlockPos();
            computer.receiveSend().ifPresent(face -> rxFaces.put(pos, face));
            computer.radioCrystal(profile).ifPresent(reference -> crystals.put(pos, reference));
            if (computer.witchCarrierOutput().isPresent()) stages.put(pos, computer);
        }
        var circuit = new CarrierWorldCircuit(level, config.carrierLimits(loaded), crystals, stages, rxFaces);
        circuit.advanceConverters();

        var endpoints = new HashMap<BlockPos, ComputerBehaviour>();
        for (var computer : seen.ticking()) {
            endpoints.put(computer.blockEntity.getBlockPos(), computer);
            computer.observeWitchFilter(computer.witchOutput().isPresent() ? "waiting" : "off", 0);
        }
        ReceiveLoop.View guides = (pos, entering) -> {
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !level.shouldTickBlocksAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || SubLevelCompat.getSpaceId(level, pos) != null) return ReceiveLoop.Gap.UNAVAILABLE;
            var block = chunk.getBlockState(pos);
            if (block.getBlock() instanceof CarrierCableBlock cable)
                return new ReceiveLoop.Guide(cable.input(block), CarrierPathBlock.output(block));
            if (!(block.getBlock() instanceof ComputerBlock)) return ReceiveLoop.Gap.OPEN;
            var owner = ComputerBlock.lowerPos(block, pos);
            var host = endpoints.get(owner);
            if (host == null || host.blockEntity.isRemoved() || chunk.getBlockEntity(owner) != host.blockEntity)
                return ReceiveLoop.Gap.UNAVAILABLE;
            if (!pos.equals(entering == Direction.UP ? owner.above() : owner)) return ReceiveLoop.Gap.WRONG_FACE;
            var filter = host.witchOutput();
            if (filter.isPresent() && filter.get().output().orElseThrow().getOpposite() != entering)
                return ReceiveLoop.Gap.WRONG_FACE;
            return new ReceiveLoop.Socket(owner, entering, filter.map(WitchReceiverFilter.Setting::width));
        };
        var stations = new ArrayList<RadioSerialNetwork.Radio>();
        var owners = new HashMap<SharedSerialChannel.Port, ComputerBehaviour>();
        var carrierFaults = new LinkedHashMap<ComputerBehaviour, String>();
        for (var computer : seen.ticking()) {
            var pos = computer.blockEntity.getBlockPos();
            var crystal = Optional.ofNullable(crystals.get(pos));
            var external = circuit.input(pos, computer.carrierInput());
            var station = computer.radioStation(profile, crystal, external);
            if (external.filter(output -> output.frequency().isEmpty()).isPresent()
                && computer.serialDevice().radioAttachment(ResidentRadioHardware.PORT).isPresent()) {
                carrierFaults.put(computer, "carrier_" + external.orElseThrow().state().name().toLowerCase(Locale.ROOT));
            }
            if (station.isEmpty()) continue;
            var radio = station.orElseThrow();
            var physical = radio.device().radioAttachment(radio.port());
            if (physical.isEmpty()) continue;
            // Retain one overflow witness for fail-closed arbitration; never allocate unbounded station arrays.
            if (stations.size() > profile.link().activePorts()) {
                radio.device().disconnect(radio.port(), "port_limit");
                computer.observeRadioDomain("port_limit");
                continue;
            }
            if (computer.receiveSend().isPresent()) {
                var loop = ReceiveLoop.trace(pos, computer.receiveSend().orElseThrow(), config.carrierLimits(loaded).maxPathBlocks(), guides);
                if (loop.connected()) {
                    radio = loop.width().apply(radio, config.witchNarrowWidth(loaded));
                    endpoints.get(loop.witch()).observeWitchFilter("connected", radio.filter().width());
                }
                radio = radio.withReceivePath(radio.filter(), loop.connected(), Optional.ofNullable(loop.witch()));
                computer.observeWitchFilter(loop.state(), loop.connected() ? radio.filter().width() : 0);
            }
            stations.add(radio);
            owners.put(physical.orElseThrow().key(), computer);
        }
        var result = state.domain.tick(stations);
        if (result.fault().isPresent()) {
            // Include ports that just queued but were never admitted into the previous medium snapshot.
            var fault = result.fault().orElseThrow();
            for (var radio : stations) radio.device().disconnect(radio.port(), fault);
        }
        for (var computer : owners.values()) computer.observeRadioDomain(result.fault().orElse(""));
        carrierFaults.forEach((computer, fault) -> {
            computer.serialDevice().disconnect(ResidentRadioHardware.PORT, fault);
            computer.observeRadioDomain(fault);
        });
        for (var observation : result.receptions()) {
            var owner = owners.get(observation.receiver());
            if (owner != null) {
                owner.observeRadio(observation);
                display(level, owner.blockEntity.getBlockPos(), observation.outcome() == RadioSerialNetwork.Reception.ACCEPTED);
            }
        }
        boolean periodic = Math.floorMod(time, Math.max(1, config.serialIndicatorInterval(loaded))) == 0;
        for (var activity : result.activity()) {
            if (periodic || activity.phase() != RadioSerialNetwork.ActivityPhase.TRANSMITTING)
                display(level, activity.position(), activity.phase() != RadioSerialNetwork.ActivityPhase.INTERRUPTED);
        }
    }

    private static void display(ServerLevel level, BlockPos pos, boolean clean) {
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return;
        try {
            level.sendParticles(clean ? ParticleTypes.END_ROD : ParticleTypes.SMOKE, pos.getX() + .5,
                pos.getY() + 2.2, pos.getZ() + .5, 1, .15, .12, .15, .02);
        } catch (RuntimeException ignored) { /* Cosmetic observers never own a frame. */ }
    }

    @SubscribeEvent public static void unloaded(LevelEvent.Unload e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        var state = WORLDS.remove(level);
        if (state != null && state.domain != null) state.domain.unload();
    }
}
