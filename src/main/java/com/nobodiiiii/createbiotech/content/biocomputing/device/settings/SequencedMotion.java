package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.InstructionSpeedModifiers;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import java.util.Map;
import java.util.UUID;

import static com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsProtocol.*;

/** Bounded native actions, not writes to the gearshift's stored program. */
public final class SequencedMotion {
    private SequencedMotion() {}

    public interface Access {
        boolean biocomputingStart(UUID id, Instruction instruction, int speed);
        UUID biocomputingMotionId();
        String biocomputingPhase();
    }

    /** Retire a saved output which loaded after its tracked source already stopped.
     * Create's ordinary source validation checks input speed, not a split shaft's output modifier.
     * Never loads a chunk or touches an untracked/native running sequence.
     */
    public static void retireStaleOutput(com.simibubi.create.content.kinetics.base.KineticBlockEntity output) {
        var level = output.getLevel();
        if (level == null || level.isClientSide || output.source == null
                || !level.hasChunkAt(output.source)) return;
        if (!(level.getBlockEntity(output.source) instanceof SequencedGearshiftBlockEntity gear)) return;
        var motion = (Access)gear;
        if (motion.biocomputingMotionId() == null
                || !(motion.biocomputingPhase().equals("interrupted") || motion.biocomputingPhase().equals("sequence_completed"))) return;
        var delta = output.getBlockPos().subtract(gear.getBlockPos());
        var face = net.minecraft.core.Direction.fromDelta(delta.getX(), delta.getY(), delta.getZ());
        if (face == null || gear.getRotationSpeedModifier(face) != 0) return;
        output.removeSource();
        output.detachKinetics();
        output.setChanged();
        output.sendData();
    }

    record Start(String code, Instruction instruction, int speed) {}

    static Start validateStart(DataValue.RecordValue args) {
        if (!(args.field("mode") instanceof DataValue.TextValue mode)
            || !(args.field("amount") instanceof DataValue.IntegerValue amount)
            || !(args.field("speed") instanceof DataValue.IntegerValue speed)) return new Start("type_mismatch", null, 0);
        var instruction = switch (mode.value()) {
            case "angle" -> SequencerInstructions.TURN_ANGLE;
            case "distance" -> SequencerInstructions.TURN_DISTANCE;
            default -> null;
        };
        if (instruction == null || amount.value() < 1 || amount.value() > instruction.maxValue
            || !java.util.Set.of(-2L, -1L, 1L, 2L).contains(speed.value()))
            return new Start("value_out_of_range", null, 0);
        int multiplier = (int)speed.value();
        return new Start("ok", new Instruction(instruction, InstructionSpeedModifiers.getByModifier(multiplier), (int)amount.value()), multiplier);
    }

    static String readiness(SequencedGearshiftBlockEntity device) {
        if (!device.isIdle() || device.computerBehaviour.hasAttachedComputer() || device.getLevel().hasNeighborSignal(device.getBlockPos()))
            return "busy";
        if (device.isOverStressed()) return "overstressed";
        return device.hasSource() && Float.isFinite(device.getSpeed()) && device.getSpeed() != 0 ? "ok" : "no_drive";
    }

    static DataValue.RecordValue state(SequencedGearshiftBlockEntity device, UUID token) {
        var motion = (Access)device;
        var id = motion.biocomputingMotionId();
        return record(Map.of("device", text("create:sequenced_gearshift"), "phase", text(motion.biocomputingPhase()),
            "availability", text(readiness(device)), "arrival", text("not_measured"),
            "action", id == null ? DataValue.NullValue.INSTANCE : text(id.toString()),
            "token", token == null ? DataValue.NullValue.INSTANCE : text(token.toString())));
    }
}
