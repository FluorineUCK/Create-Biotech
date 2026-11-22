package com.nobodiiiii.createbiotech.content.biocomputing.device.carrier;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CarrierHardwareConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierCircuit;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** One same-tick view, taken after block entities tick. No tracing loads or tickets chunks. */
public final class CarrierWorldCircuit {
    private final ServerLevel level;
    private final long tick;
    private final CarrierHardwareConfig.Limits limits;
    private final Map<BlockPos, Direction> receiveFaces;
    private final Map<BlockPos, CarrierFrequency> oscillators;
    private final Map<BlockPos, ComputerBehaviour> stages;
    private final Map<CarrierCircuit.Pin, CarrierCircuit.Hop> seen = new HashMap<>();

    public CarrierWorldCircuit(ServerLevel level, CarrierHardwareConfig.Limits limits, Map<BlockPos, CarrierFrequency> oscillators,
                               Map<BlockPos, ComputerBehaviour> stages, Map<BlockPos, Direction> receiveFaces) {
        this.receiveFaces = Map.copyOf(receiveFaces);
        this.level = level;
        this.tick = level.getGameTime();
        this.limits = limits;
        this.oscillators = Map.copyOf(oscillators);
        this.stages = new LinkedHashMap<>(stages);
    }

    public void advanceConverters() {
        // Physical acquisition runs even if no radio is currently sampling this output.
        stages.forEach((pos, stage) -> stage.witchCarrierOutput().ifPresent(face -> {
            var sampled = output(new CarrierCircuit.Pin(portBlock(pos, face), face));
            // A receiver's trace limit does not invalidate upstream components. But if this
            // stage itself cannot reach its reference, it cannot retain an earlier lock.
            if (sampled.state() == CarrierClock.State.PATH_LIMIT)
                stage.advanceWitchCarrier(tick, sampled, limits);
        }));
    }

    private static BlockPos portBlock(BlockPos lower, Direction face) {
        return face == Direction.UP ? lower.above() : lower;
    }

    private boolean residentAt(CarrierCircuit.Pin pin) {
        var pos = pin.face() == Direction.UP ? pin.position().below() : pin.position();
        return oscillators.containsKey(pos) || stages.containsKey(pos);
    }

    /** Empty means player selected the internal oscillator; an inactive face stays explicitly external. */
    public Optional<CarrierClock.Output> input(BlockPos computer, Optional<Direction> face) {
        return face.map(direction -> {
            var pin = CarrierCircuit.Pin.opposite(portBlock(computer, direction), direction);
            // A second Computer resting beside rf0 is not a cable or an adapter.
            return residentAt(pin)
                ? CarrierClock.Output.unavailable(CarrierClock.State.WRONG_FACE) : output(pin);
        });
    }

    private CarrierClock.Output output(CarrierCircuit.Pin pin) {
        return CarrierCircuit.sample(pin, limits.maxPathBlocks(), value -> seen.computeIfAbsent(value, this::observe));
    }

    private CarrierCircuit.Hop observe(CarrierCircuit.Pin pin) {
        var pos = pin.position();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !level.shouldTickBlocksAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
            || SubLevelCompat.getSpaceId(level, pos) != null) return fault(CarrierClock.State.UNAVAILABLE);

        var state = chunk.getBlockState(pos);
        var owner = ComputerBlock.lowerPos(state, pos);
        var stage = stages.get(owner);
        if (stage != null) {
            if (stage.blockEntity.isRemoved() || chunk.getBlockEntity(owner) != stage.blockEntity)
                return fault(CarrierClock.State.UNAVAILABLE);
            if (!pos.equals(portBlock(owner, pin.face())) || stage.witchCarrierOutput().filter(pin.face()::equals).isEmpty())
                return fault(CarrierClock.State.WRONG_FACE);
            var input = stage.witchCarrierInput();
            if (input.isEmpty()) return fault(CarrierClock.State.UNAVAILABLE);
            var upstream = CarrierCircuit.Pin.opposite(portBlock(owner, input.get()), input.get());
            if (residentAt(upstream))
                return new CarrierCircuit.Terminal(stage.advanceWitchCarrier(tick,
                    CarrierClock.Output.unavailable(CarrierClock.State.WRONG_FACE), limits));
            return new CarrierCircuit.Link(upstream, reference -> stage.advanceWitchCarrier(tick, reference, limits));
        }
        if (state.getBlock() instanceof CarrierCableBlock cable) {
            if (CarrierPathBlock.output(state) != pin.face() || cable.input(state) == pin.face())
                return fault(CarrierClock.State.WRONG_FACE);
            return CarrierCircuit.Link.pass(CarrierCircuit.Pin.opposite(pos, cable.input(state)));
        }
        var reserved = receiveFaces.get(owner);
        if (reserved != null && reserved.getAxis() == pin.face().getAxis()) return fault(CarrierClock.State.WRONG_FACE);
        var source = pos.equals(portBlock(owner, pin.face())) ? oscillators.get(owner) : null;
        return source == null ? fault(CarrierClock.State.UNAVAILABLE)
            : new CarrierCircuit.Terminal(CarrierClock.Output.valid(source));
    }

    private static CarrierCircuit.Terminal fault(CarrierClock.State code) {
        return new CarrierCircuit.Terminal(CarrierClock.Output.unavailable(code));
    }
}
