package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import net.minecraft.core.Direction;

/** Signals emitted from the authorized target toward its physically attached reader. */
public final class RedstoneReadComponent {
    public static final String ID = "create_biotech:redstone";

    private RedstoneReadComponent() {}

    public static void register(ReadComponentRegistry.Builder<BlockReadContext> builder) {
        builder.register(BuiltinReadComponents.provider(ID, new DataType.RecordType(Map.of(
            "face", TEXT, "signal", INTEGER, "direct", INTEGER, "comparator", ANY)),
            ReadComponentDescriptor.UpdateHint.TICK, RedstoneReadComponent::read));
    }

    private static DataValue read(BlockReadContext context) {
        var level = context.level();
        var target = context.target();
        var state = context.state();
        // Vanilla signal API directions point opposite to the physical emitting surface.
        Direction querySide = context.face().getOpposite();
        if (state.shouldCheckWeakPower(level, target, querySide)) {
            // Vanilla getSignal may inspect all six direct-power neighbors of a conductor.
            // Out-of-build-height neighbors are already known air, without a chunk query.
            for (Direction direction : Direction.values()) {
                var neighbor = target.relative(direction);
                if (!level.isOutsideBuildHeight(neighbor) && !level.isLoaded(neighbor))
                    return new DataValue.ErrorValue("target_unloaded", "");
            }
        }
        DataValue comparator = comparator(context);
        if (comparator instanceof DataValue.ErrorValue error && error.code().equals("target_unloaded"))
            return error;
        return new DataValue.RecordValue(Map.of(
            "face", new DataValue.TextValue(context.face().getName()),
            "signal", new DataValue.IntegerValue(level.getSignal(target, querySide)),
            "direct", new DataValue.IntegerValue(level.getDirectSignal(target, querySide)),
            "comparator", comparator));
    }

    private static DataValue comparator(BlockReadContext context) {
        var state = context.state();
        if (!state.hasAnalogOutputSignal()) return new DataValue.ErrorValue("unsupported", "");
        var denied = VanillaContainerReadGuard.comparator(context);
        if (denied != null) return denied;
        return new DataValue.IntegerValue(state.getAnalogOutputSignal(context.level(), context.target()));
    }
}
