package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputState;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.mixin.BiocomputingComparatorAccess;
import com.nobodiiiii.createbiotech.mixin.BiocomputingScrollRangeAccess;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.contraptions.IControlContraption.MovementMode;
import com.simibubi.create.content.contraptions.IControlContraption.RotationMode;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlock;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity.RotationDirection;
import com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlockEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity.CartMovementMode;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.decoration.slidingDoor.DoorControl;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock.WhistleSize;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlockEntity;
import com.simibubi.create.content.equipment.clipboard.ClipboardCloneable;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlock;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity;
import com.simibubi.create.content.redstone.diodes.BrassDiodeBlock;
import com.simibubi.create.content.redstone.diodes.BrassDiodeBlockEntity;
import com.simibubi.create.content.redstone.diodes.BrassDiodeScrollValueBehaviour;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlock;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.BulkScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Native configuration only. All values are copies; no inventory, scheduler or arbitrary NBT entry. */
final class ComponentSettings {
    private static final ResourceLocation AERO_BURNER = ResourceLocation.fromNamespaceAndPath("aeronautics", "adjustable_burner");
    private static final ResourceLocation AERO_STEAM_VENT = ResourceLocation.fromNamespaceAndPath("aeronautics", "steam_vent");
    private static final ResourceLocation AERO_PROPELLER = ResourceLocation.fromNamespaceAndPath("aeronautics", "propeller_bearing");
    private static final ResourceLocation AERO_GYRO_PROPELLER = ResourceLocation.fromNamespaceAndPath("aeronautics", "gyroscopic_propeller_bearing");
    private static final ResourceLocation SIM_NAMEPLATE = ResourceLocation.fromNamespaceAndPath("simulated", "nameplate");
    private static final ResourceLocation SIM_ALTITUDE = ResourceLocation.fromNamespaceAndPath("simulated", "altitude_sensor");
    private static final ResourceLocation SIM_RECEIVER = ResourceLocation.fromNamespaceAndPath("simulated", "modulating_linked_receiver");
    private static final ResourceLocation SIM_INDUCTOR = ResourceLocation.fromNamespaceAndPath("simulated", "redstone_inductor");
    private static final ResourceLocation SIM_ACCUMULATOR = ResourceLocation.fromNamespaceAndPath("simulated", "redstone_accumulator");
    private static final String CLIPBOARD_INVERTED = "Inverted";
    private static final ResourceLocation SIM_OPTICAL_SENSOR = ResourceLocation.fromNamespaceAndPath("simulated", "optical_sensor");
    private static final ResourceLocation SIM_THROTTLE_LEVER = ResourceLocation.fromNamespaceAndPath("simulated", "throttle_lever");
    private static final ResourceLocation SIM_LASER_POINTER = ResourceLocation.fromNamespaceAndPath("simulated", "laser_pointer");
    private static final String MAX_BEAM_RANGE = "max_beam_range";
    private static final ResourceLocation SIM_GIMBAL_SENSOR = ResourceLocation.fromNamespaceAndPath("simulated", "gimbal_sensor");
    private static final ResourceLocation SIM_VELOCITY_SENSOR = ResourceLocation.fromNamespaceAndPath("simulated", "velocity_sensor");
    private static final ResourceLocation SIM_TORSION_SPRING = ResourceLocation.fromNamespaceAndPath("simulated", "torsion_spring");
    private static final ResourceLocation SIM_STEERING_WHEEL = ResourceLocation.fromNamespaceAndPath("simulated", "steering_wheel");
    // The native gimbal clipboard accepts only these two calibration settings.
    private static final String GIMBAL_PRIMARY = "ScrollValue1", GIMBAL_SECONDARY = "ScrollValue2";
    private static final ResourceLocation SIM_PORTABLE_ENGINE = ResourceLocation.fromNamespaceAndPath("simulated", "portable_engine");
    private static final ResourceLocation SIM_SWIVEL_BEARING = ResourceLocation.fromNamespaceAndPath("simulated", "swivel_bearing");
    private static final String SWIVEL_LOCKING_OPTION =
        "dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity$LockingSetting";
    private static final ResourceLocation OFFROAD_WHEEL_MOUNT = ResourceLocation.fromNamespaceAndPath("offroad", "wheel_mount");
    private static final Set<ResourceLocation> NO_PLAYER_SETTINGS = Set.of(
        ResourceLocation.fromNamespaceAndPath("offroad", "borehead_bearing"),
        ResourceLocation.fromNamespaceAndPath("offroad", "rockcutting_wheel"));
    private static final Field DELAY = new Field("delay", "redstone_tick", new IntRange(
        RepeaterBlock.DELAY.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElseThrow(),
        RepeaterBlock.DELAY.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElseThrow()), "reject_pending_edge");
    private static final Field MODE = new Field("mode", "mode",
        new Choices(List.of(ComparatorMode.COMPARE.getSerializedName(), ComparatorMode.SUBTRACT.getSerializedName())), "native_recalculate");
    private static final Field DEPLOYER_MODE = new Field("mode", "mode",
        new Choices(List.of("use", "punch")), "requires_physical_lock_and_idle");
    private static final Field ARM_SELECTION = new Field("selection_mode", "mode",
        new Choices(List.of("round_robin", "forced_round_robin", "prefer_first")), "reject_active_transfer");
    private static final Field INVERTED = new Field("inverted", "boolean", new Toggle(), "native_invert_output");
    private static final Field THRESHOLD_INVERTED = new Field("inverted", "boolean", new Toggle(), "reject_pending_edge");
    private static final Field THRUST_DIRECTION = new Field("thrust_direction", "mode",
        new Choices(List.of("right_handed", "left_handed")), "native_thrust_direction");
    private static final Field LOCKING_MODE = new Field("locking_mode", "mode",
        new Choices(List.of("locked_always", "locked_default", "unlocked_default", "unlocked_always")), "native_recalculate");
    private static final Field ROTARY_MOVEMENT_MODE = new Field("movement_mode", "mode",
        new Choices(List.of("rotate_place", "rotate_place_returned", "rotate_never_place")), "reject_active_contraption");
    private static final Field CART_MOVEMENT_MODE = new Field("movement_mode", "mode",
        new Choices(List.of("rotate", "rotate_paused", "rotation_locked")), "next_cart_assembly");
    private static final Field ROLLER_MODE = new Field("roller_mode", "mode",
        new Choices(List.of("tunnel_pave", "straight_fill", "wide_fill")), "requires_isolated_roller");
    private static final Field WHISTLE_SIZE = new Field("whistle_size", "mode",
        new Choices(WhistleBlock.SIZE.getPossibleValues().stream().map(WhistleSize::getSerializedName).toList()), "reject_powered_whistle");
    private static final Field TUNNEL_DISTRIBUTION = new Field("distribution_mode", "mode",
        new Choices(List.of("split", "forced_split", "round_robin", "forced_round_robin", "prefer_nearest", "randomize", "synchronize")),
        "requires_idle_single_tunnel");
    private static final Field CLOCK_HANDS = new Field("clock_hands", "mode",
        new Choices(List.of("hour_first", "minute_first", "hour_first_24")), "reject_active_contraption");
    private static final Field LINEAR_MOVEMENT_MODE = new Field("movement_mode", "mode",
        new Choices(List.of("move_place", "move_place_returned", "move_never_place")), "reject_active_contraption");
    private static final Field ROTATION_DIRECTION = new Field("rotation_direction", "mode",
        new Choices(List.of("clockwise", "counter_clockwise")), "native_generated_rotation");
    // Match the two length limits of Create's player-facing ElevatorContactScreen.
    private static final Field FLOOR_NAMES = new Field("floor_names", "text", new FloorNames(4, 30), "native_floor_labels");
    private static final List<String> DOOR_MODES = java.util.Arrays.stream(DoorControl.values())
        .map(value -> value.name().toLowerCase(java.util.Locale.ROOT)).toList();
    private static final Field DOOR_CONTROL = new Field("door_control", "mode", new Choices(DOOR_MODES), "native_door_policy");
    // Row order in Create's native extraction value board.
    private static final Choices EXTRACTION_MODES = new Choices(List.of("up_to", "exactly"));

    private ComponentSettings() {}

    sealed interface Format permits IntRange, Choices, Toggle, ThresholdPair, DistancePair, AltitudePair, AnglePair,
            VelocityCalibration, FloorNames, ExtractionBatch, LabelText {
        String validate(DataValue value);
    }
    record Toggle() implements Format {
        @Override public String validate(DataValue value) {
            return value instanceof DataValue.BooleanValue ? "ok" : "type_mismatch";
        }
    }
    record IntRange(int minimum, int maximum, boolean zeroAllowed) implements Format {
        IntRange(int minimum, int maximum) { this(minimum, maximum, true); }
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.IntegerValue number)) return "type_mismatch";
            return number.value() >= minimum && number.value() <= maximum && (zeroAllowed || number.value() != 0)
                ? "ok" : "value_out_of_range";
        }
    }
    record Choices(List<String> values) implements Format {
        Choices { values = List.copyOf(values); }
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.TextValue text)) return "type_mismatch";
            return values.contains(text.value()) ? "ok" : "value_out_of_range";
        }
    }
    record ExtractionBatch(IntRange amount) implements Format {
        Choices mode() { return EXTRACTION_MODES; }
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.RecordValue batch) || !batch.fields().keySet().equals(Set.of("amount", "mode")))
                return "type_mismatch";
            var validity = amount.validate(batch.field("amount"));
            return validity.equals("ok") ? mode().validate(batch.field("mode")) : validity;
        }
    }
    record ThresholdPair(IntRange limits) implements Format {
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.RecordValue pair) || !pair.fields().keySet().equals(Set.of("off_below", "on_above")))
                return "type_mismatch";
            var lower = pair.field("off_below");
            var upper = pair.field("on_above");
            for (var bound : List.of(lower, upper)) {
                var result = limits.validate(bound);
                if (!result.equals("ok")) return result;
            }
            return integer(lower) < integer(upper) ? "ok" : "invalid_threshold_order";
        }
    }
    record DistancePair(IntRange limits) implements Format {
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.RecordValue pair) || !pair.fields().keySet().equals(Set.of("minimum", "maximum")))
                return "type_mismatch";
            var min = pair.field("minimum");
            var max = pair.field("maximum");
            for (var bound : List.of(min, max)) {
                var result = limits.validate(bound);
                if (!result.equals("ok")) return result;
            }
            return integer(min) <= integer(max) ? "ok" : "invalid_setting_pair";
        }
    }
    record AltitudePair() implements Format {
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.RecordValue pair) || !pair.fields().keySet().equals(Set.of("zero_at", "full_at"))
                || !(pair.field("zero_at") instanceof DataValue.DecimalValue zero)
                || !(pair.field("full_at") instanceof DataValue.DecimalValue full)) return "type_mismatch";
            if (!Double.isFinite(zero.value()) || !Double.isFinite(full.value())
                || zero.value() < 0 || zero.value() > 1 || full.value() < 0 || full.value() > 1) return "value_out_of_range";
            return (float)zero.value() == (float)full.value() ? "invalid_setting_pair" : "ok";
        }
    }
    record AnglePair(IntRange limits) implements Format {
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.RecordValue pair) || !pair.fields().keySet().equals(Set.of("primary", "secondary")))
                return "type_mismatch";
            String primary = limits.validate(pair.field("primary"));
            return primary.equals("ok") ? limits.validate(pair.field("secondary")) : primary;
        }
    }
    // Same order as the two rows of the native velocity sensor board.
    private static final Choices VELOCITY_DIRECTIONS = new Choices(List.of("away", "towards"));
    record VelocityCalibration(IntRange speed) implements Format {
        Choices direction() { return VELOCITY_DIRECTIONS; }
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.RecordValue pair) || !pair.fields().keySet().equals(Set.of("full_speed", "direction")))
                return "type_mismatch";
            String validity = speed.validate(pair.field("full_speed"));
            return validity.equals("ok") ? direction().validate(pair.field("direction")) : validity;
        }
    }
    record LabelText(int maximumBytes) implements Format {
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.TextValue text)) return "type_mismatch";
            var label = text.value();
            if (label.length() > maximumBytes) return "value_out_of_range";
            if (FloorNames.invalidLiteral(label)) return "invalid_label";
            return label.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maximumBytes ? "ok" : "value_out_of_range";
        }
    }
    record FloorNames(int shortMaximum, int longMaximum) implements Format {
        @Override public String validate(DataValue value) {
            if (!(value instanceof DataValue.RecordValue names) || !names.fields().keySet().equals(Set.of("short", "long"))
                || !(names.field("short") instanceof DataValue.TextValue shortName)
                || !(names.field("long") instanceof DataValue.TextValue longName)) return "type_mismatch";
            if (shortName.value().length() > shortMaximum || longName.value().length() > longMaximum)
                return "value_out_of_range";
            return invalidLiteral(shortName.value()) || invalidLiteral(longName.value()) ? "invalid_floor_name" : "ok";
        }
        private static boolean invalidLiteral(String name) {
            return name.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == 0xA7 || cp == 0x2028 || cp == 0x2029
                || cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
        }
    }
    private static int integer(DataValue value) { return (int)((DataValue.IntegerValue)value).value(); }
    record Field(String name, String unit, Format format, String timing) {}
    record Observation(ResourceLocation device, Field field, DataValue value, boolean busy, DisplayLinkRows.Binding binding) {
        Observation(ResourceLocation device, Field field, DataValue value, boolean busy) { this(device, field, value, busy, null); }
    }
    record Edit(String code, BooleanSupplier action) {
        boolean apply() { return action.getAsBoolean(); }
        static Edit reject(String code) { return new Edit(code, () -> false); }
    }
    private record NativeTarget(BlockState state, Object identity, Observation observation, java.util.function.Predicate<DataValue> setter) {}

    static Optional<Observation> observe(ServerLevel level, BlockPos pos) {
        return observe(level, pos, "");
    }

    static Optional<Observation> observe(ServerLevel level, BlockPos pos, String field) {
        return resolve(level, pos, field).map(NativeTarget::observation);
    }

    static List<String> fields(Observation primary) {
        var id = primary.device();
        if (id.equals(AllBlocks.PULSE_TIMER.getId()) || id.equals(AllBlocks.PULSE_REPEATER.getId()) || id.equals(AllBlocks.PULSE_EXTENDER.getId())
            || id.equals(SIM_INDUCTOR) || id.equals(SIM_ACCUMULATOR))
            return List.of("interval", INVERTED.name());
        if (id.equals(SIM_LASER_POINTER))
            return List.of(MAX_BEAM_RANGE, INVERTED.name());
        if (id.equals(AllBlocks.THRESHOLD_SWITCH.getId()))
            return List.of("thresholds", THRESHOLD_INVERTED.name());
        if (id.equals(AllBlocks.ELEVATOR_CONTACT.getId()))
            return List.of(FLOOR_NAMES.name(), DOOR_CONTROL.name());
        return List.of(primary.field().name());
    }

    static boolean noPlayerSettings(BlockState state) {
        return NO_PLAYER_SETTINGS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    static Optional<DataValue.RecordValue> motion(ServerLevel level, BlockPos pos) {
        var chunk = tickingChunk(level, pos);
        if (chunk == null || !AllBlocks.ROTATION_SPEED_CONTROLLER.has(chunk.getBlockState(pos))
            || !(chunk.getBlockEntity(pos) instanceof SpeedControllerBlockEntity controller)
            || controller.isRemoved() || controller.targetSpeed == null) return Optional.empty();
        float actual = controller.getSpeed();
        if (!Float.isFinite(actual)) return Optional.empty();
        int target = controller.targetSpeed.getValue();
        boolean overloaded = controller.isOverStressed();
        return Optional.of(new DataValue.RecordValue(Map.of(
            "target_rpm", new DataValue.IntegerValue(target), "actual_rpm", new DataValue.DecimalValue(actual == 0 ? 0 : actual),
            "has_source", new DataValue.BooleanValue(controller.hasSource()),
            "overstressed", new DataValue.BooleanValue(overloaded),
            "reached", new DataValue.BooleanValue(!overloaded && (target == 0 || controller.hasSource()) && actual == target))));
    }

    /** Check at both preparation and commit. The external port also fences target and session across wire requests. */
    static Edit plan(ServerLevel level, BlockPos pos, Observation expected, DataValue value) {
        return plan(level, pos, expected, value, null);
    }

    static Edit plan(ServerLevel level, BlockPos pos, Observation expected, DataValue value, ServerPlayer actor) {
        var target = resolve(level, pos, expected.field().name(), actor).orElse(null);
        if (target == null || !target.observation().device().equals(expected.device())) return Edit.reject("target_changed");
        var actual = target.observation();
        if (!java.util.Objects.equals(actual.binding(), expected.binding())) return Edit.reject("target_changed");
        if (!actual.field().equals(expected.field()) || !actual.value().equals(expected.value())) return Edit.reject("version_conflict");
        String validation = actual.field().format().validate(value);
        if (!validation.equals("ok")) return Edit.reject(validation);
        if (actual.binding() != null && !actual.value().equals(value)) {
            String availability = DisplayLinkRows.canSet(actual.binding(), integer(value) - 1, actor);
            if (!availability.equals("ok")) return Edit.reject(availability);
        }
        if (actual.field().equals(DEPLOYER_MODE) && target.identity() instanceof DeployerBlockEntity deployer) {
            if (expected.busy()) return Edit.reject("busy");
            String readiness = DeployerControl.modeWriteStatus(deployer);
            if (!readiness.equals("ok")) return Edit.reject(readiness);
        }
        if ((actual.field().equals(ARM_SELECTION) || actual.field().format() instanceof LabelText)
            && (expected.busy() || actual.busy())) return Edit.reject("busy");
        if (actual.value().equals(value)) return new Edit("unchanged", () -> same(target, resolve(level, pos, actual.field().name()).orElse(null)));
        if (actual.busy()) return Edit.reject("busy");
        return new Edit("applied", () -> same(target, resolve(level, pos, actual.field().name()).orElse(null)) && target.setter().test(value));
    }

    private static boolean same(NativeTarget observed, NativeTarget now) {
        return now != null && now.identity() == observed.identity() && now.state() == observed.state()
            && now.observation().equals(observed.observation());
    }

    private static net.minecraft.world.level.chunk.LevelChunk tickingChunk(ServerLevel level, BlockPos pos) {
        if (!level.shouldTickBlocksAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return null;
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }
    private static Optional<NativeTarget> resolve(ServerLevel level, BlockPos pos, String field) {
        return resolve(level, pos, field, null);
    }

    private static Optional<NativeTarget> resolve(ServerLevel level, BlockPos pos, String field, ServerPlayer actor) {
        return resolveNative(level, pos, field, actor)
            .filter(target -> field.isEmpty() || target.observation().field().name().equals(field));
    }

    private static boolean isolatedRoller(ServerLevel level, BlockPos pos, BlockState state) {
        // The native setter propagates both mode and material to same-state rollers in both lateral directions.
        // Prove it stops at the immediate neighbours before using the setter; never load chunks to check.
        Direction side = state.getValue(RollerBlock.FACING).getClockWise();
        for (Direction direction : List.of(side, side.getOpposite())) {
            var neighbour = pos.relative(direction);
            var neighbourChunk = tickingChunk(level, neighbour);
            if (neighbourChunk == null || neighbourChunk.getBlockState(neighbour) == state) return false;
        }
        return true;
    }

    private static boolean tunnelBusy(ServerLevel level, BlockPos pos, BlockState state, BrassTunnelBlockEntity tunnel) {
        // The public intake check includes buffered stock and an unfinished synchronized release.
        if (!tunnel.canTakeItems() || tunnel.isConnected(true) || tunnel.isConnected(false)) return true;
        var axis = state.getValue(BeltTunnelBlock.HORIZONTAL_AXIS);
        var side = Direction.get(Direction.AxisDirection.POSITIVE, axis).getClockWise();
        for (var direction : List.of(side, side.getOpposite())) {
            var adjacent = pos.relative(direction);
            var chunk = tickingChunk(level, adjacent);
            if (chunk == null) return true;
            var neighbor = chunk.getBlockState(adjacent);
            // The setter propagates to matching lateral tunnels; also fence a new or stale native link.
            if (AllBlocks.BRASS_TUNNEL.has(neighbor) && neighbor.getValue(BeltTunnelBlock.HORIZONTAL_AXIS) == axis) return true;
        }
        return false;
    }

    private static DataValue.RecordValue gimbalAngles(ServerLevel level, ScrollValueBehaviour settings) {
        var clipboard = new CompoundTag();
        if (!settings.writeToClipboard(level.registryAccess(), clipboard, Direction.UP)
            || !clipboard.contains(GIMBAL_PRIMARY, Tag.TAG_INT) || !clipboard.contains(GIMBAL_SECONDARY, Tag.TAG_INT)) return null;
        return new DataValue.RecordValue(Map.of(
            "primary", new DataValue.IntegerValue(clipboard.getInt(GIMBAL_PRIMARY)),
            "secondary", new DataValue.IntegerValue(clipboard.getInt(GIMBAL_SECONDARY))));
    }

    private static Optional<Boolean> inductorInverted(ServerLevel level, ClipboardCloneable settings) {
        var clipboard = new CompoundTag();
        return settings.writeToClipboard(level.registryAccess(), clipboard, Direction.UP)
            && clipboard.contains(CLIPBOARD_INVERTED, Tag.TAG_BYTE)
            ? Optional.of(clipboard.getBoolean(CLIPBOARD_INVERTED)) : Optional.empty();
    }

    private static Optional<NativeTarget> nativeEnumOption(BlockState state, SmartBlockEntity device, ResourceLocation id,
            Field field, Class<? extends Enum<?>> nativeType, boolean busy) {
        return nativeEnumOption(state, device, id, field, nativeType::equals, busy);
    }

    private static Optional<NativeTarget> nativeEnumOption(BlockState state, SmartBlockEntity device, ResourceLocation id,
            Field field, java.util.function.Predicate<Class<?>> matchesNativeType, boolean busy) {
        if (!(BlockEntityBehaviour.get(device, ScrollValueBehaviour.TYPE) instanceof ScrollOptionBehaviour<?> option)
                || !option.isActive()) return Optional.empty();
        return nativeEnumOption(state, device, id, field, matchesNativeType, busy, option);
    }

    private static Optional<NativeTarget> nativeEnumOption(BlockState state, SmartBlockEntity device, ResourceLocation id,
            Field field, java.util.function.Predicate<Class<?>> matchesNativeType, boolean busy, ScrollOptionBehaviour<?> option) {
        var selected = option.get();
        var nativeType = selected.getDeclaringClass();
        if (!matchesNativeType.test(nativeType)) return Optional.empty();
        var names = ((Choices)field.format()).values();
        var nativeNames = java.util.Arrays.stream(nativeType.getEnumConstants())
            .map(value -> value.name().toLowerCase(java.util.Locale.ROOT)).toList();
        if (!names.equals(nativeNames)) return Optional.empty();
        return Optional.of(new NativeTarget(state, device,
            new Observation(id, field, new DataValue.TextValue(names.get(selected.ordinal())), busy), value -> {
                var index = names.indexOf(((DataValue.TextValue)value).value());
                option.setValue(index);
                return option.getValue() == index;
            }));
    }

    private static boolean steamShaftBusy(ServerLevel level, BlockPos pos, BlockState state) {
        var shaftPos = SteamEngineBlock.getShaftPos(state, pos);
        var chunk = tickingChunk(level, shaftPos);
        if (chunk == null) return true;
        if (!AllBlocks.POWERED_SHAFT.has(chunk.getBlockState(shaftPos))) return false;
        return !(chunk.getBlockEntity(shaftPos) instanceof PoweredShaftBlockEntity shaft)
            || shaft.isRemoved() || shaft.hasSource() || !shaft.canBePoweredBy(pos);
    }

    private static boolean withinSingleWorldNameplate(ServerLevel level, BlockPos pos, BlockState state) {
        // Native editing propagates sideways and renames the containing Sable body. Neither is authorized by this port.
        if (!SubLevelCompat.matchesSpace(level, pos, null)) return false;
        Direction side = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise();
        for (Direction direction : List.of(side, side.getOpposite())) {
            var adjacent = pos.relative(direction);
            var neighbourChunk = tickingChunk(level, adjacent);
            if (neighbourChunk == null || !SubLevelCompat.matchesSpace(level, adjacent, null)) return false;
            var neighbour = neighbourChunk.getBlockState(adjacent);
            if (neighbour.is(state.getBlock()) && neighbour.getValue(BlockStateProperties.HORIZONTAL_FACING)
                == state.getValue(BlockStateProperties.HORIZONTAL_FACING)) return false;
        }
        return true;
    }

    private static Optional<NativeTarget> resolveNameplate(ServerLevel level, BlockPos pos, BlockState state,
            ResourceLocation id, SmartBlockEntity plate, ClipboardCloneable nativeClip, ServerPlayer actor) {
        // Read only this entity's fixed layout. Asking the clipboard to read a follower could load its controller.
        var saved = plate.saveWithoutMetadata(level.registryAccess());
        boolean editable = saved.contains("Width", Tag.TAG_INT) && saved.getInt("Width") == 1
            && saved.contains("Waxed", Tag.TAG_BYTE) && !saved.getBoolean("Waxed")
            && saved.contains("TextColor", Tag.TAG_INT) && withinSingleWorldNameplate(level, pos, state);
        var format = new LabelText(CBConfigs.SERVER.biocomputing.labelOutputBytes(CBConfigs.SERVER_SPEC.isLoaded()));
        var field = new Field("label_text", "text", format, "isolated_unwaxed_nameplate");
        var previous = new DataValue.TextValue(saved.getString("Name"));
        var visible = new LabelText(LabelOutputState.FORMAT_MAX_BYTES).validate(previous).equals("ok");
        var value = visible ? previous : DataValue.NullValue.INSTANCE;
        return Optional.of(new NativeTarget(state, plate, new Observation(id, field, value, !editable || !visible), update -> {
            // This is the native clipboard operation, not an NBT load. Copy existing dye; only the validated name is new.
            var nativeSettings = new CompoundTag();
            nativeSettings.putString("StoredName", ((DataValue.TextValue)update).value());
            nativeSettings.putInt("TextColor", saved.getInt("TextColor"));
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (!nativeClip.readFromClipboard(level.registryAccess(), nativeSettings, actor, facing, false)) return false;
            plate.setChanged();
            return true;
        }));
    }

    private static Optional<NativeTarget> resolveNative(ServerLevel level, BlockPos pos, String requestedField, ServerPlayer actor) {
        var chunk = tickingChunk(level, pos);
        if (chunk == null) return Optional.empty();
        var state = chunk.getBlockState(pos);
        var block = state.getBlock();
        var id = BuiltInRegistries.BLOCK.getKey(block);
        if (state.is(Blocks.REPEATER)) {
            var ticks = level.getBlockTicks();
            boolean busy = ticks.hasScheduledTick(pos, block) || ticks.willTickThisTick(pos, block);
            return Optional.of(new NativeTarget(state, block, new Observation(id, DELAY,
                new DataValue.IntegerValue(state.getValue(RepeaterBlock.DELAY)), busy), value ->
                    level.setBlock(pos, state.setValue(RepeaterBlock.DELAY, (int)((DataValue.IntegerValue)value).value()), Block.UPDATE_ALL)));
        }
        var entity = chunk.getBlockEntity(pos);
        if (AllBlocks.DISPLAY_LINK.has(state) && entity instanceof DisplayLinkBlockEntity link) {
            var binding = DisplayLinkRows.observe(level, link);
            if (binding == null) return Optional.empty();
            var field = new Field(DisplayLinkRows.FIELD, "row", new IntRange(2, binding.rows()), "reserve_free_display_rows");
            return Optional.of(new NativeTarget(state, link,
                new Observation(id, field, new DataValue.IntegerValue(link.targetLine + 1L), false, binding),
                update -> DisplayLinkRows.set(binding, integer(update) - 1, actor)));
        }
        if (id.getNamespace().equals(SIM_NAMEPLATE.getNamespace()) && id.getPath().endsWith("_nameplate")
            && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            && entity instanceof SmartBlockEntity plate && !plate.isRemoved() && plate instanceof ClipboardCloneable nativeClip
            && SIM_NAMEPLATE.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(plate.getType()))
            && nativeClip.getClipboardKey().equals("Name")) {
            return resolveNameplate(level, pos, state, id, plate, nativeClip, actor);
        }
        boolean brassFunnel = entity instanceof FunnelBlockEntity
            && (AllBlocks.BRASS_FUNNEL.has(state) || AllBlocks.BRASS_BELT_FUNNEL.has(state));
        boolean smartChute = entity instanceof SmartChuteBlockEntity && AllBlocks.SMART_CHUTE.has(state);
        if ((brassFunnel || smartChute) && entity instanceof SmartBlockEntity extractionDevice && !extractionDevice.isRemoved()) {
            var filtering = BlockEntityBehaviour.get(extractionDevice, FilteringBehaviour.TYPE);
            // The native board is unavailable in non-extracting arrangements or with a non-stackable sample.
            if (filtering == null || !filtering.isActive() || !filtering.acceptsValueSettings()) return Optional.empty();
            var current = filtering.getValueSettings();
            var range = new IntRange(1, filtering.getMaxStackSize());
            var field = new Field("extraction_batch", "item", new ExtractionBatch(range), "native_extraction_batch");
            var batch = new DataValue.RecordValue(Map.of("amount", new DataValue.IntegerValue(current.value()),
                "mode", new DataValue.TextValue(EXTRACTION_MODES.values().get(current.row()))));
            return Optional.of(new NativeTarget(state, extractionDevice, new Observation(id, field, batch, false), update -> {
                var desired = (DataValue.RecordValue)update;
                var mode = ((DataValue.TextValue)desired.field("mode")).value();
                var nativeValue = new ValueSettings(EXTRACTION_MODES.values().indexOf(mode), integer(desired.field("amount")));
                filtering.setValueSettings(actor, nativeValue, false);
                return filtering.getValueSettings().equals(nativeValue);
            }));
        }
        if (AllBlocks.BRASS_TUNNEL.has(state) && entity instanceof BrassTunnelBlockEntity tunnel && !tunnel.isRemoved()) {
            return nativeEnumOption(state, tunnel, id, TUNNEL_DISTRIBUTION, BrassTunnelBlockEntity.SelectionMode.class,
                tunnelBusy(level, pos, state, tunnel));
        }
        if (AllBlocks.STEAM_WHISTLE.has(state) && entity instanceof WhistleBlockEntity whistle && !whistle.isRemoved()) {
            var support = pos.relative(WhistleBlock.getAttachedDirection(state));
            var supportChunk = tickingChunk(level, support);
            boolean busy = state.getValue(WhistleBlock.POWERED) || supportChunk == null
                || !FluidTankBlock.isTank(supportChunk.getBlockState(support));
            return Optional.of(new NativeTarget(state, whistle,
                new Observation(id, WHISTLE_SIZE, new DataValue.TextValue(state.getValue(WhistleBlock.SIZE).getSerializedName()), busy),
                value -> WhistleBlock.SIZE.getValue(((DataValue.TextValue)value).value())
                    // Same block update as the native wrench. Attached extensions resize via their own shape updates.
                    .map(size -> level.setBlock(pos, state.setValue(WhistleBlock.SIZE, size), Block.UPDATE_ALL)).orElse(false)));
        }
        if (AllBlocks.MECHANICAL_ARM.has(state) && entity instanceof ArmBlockEntity arm && !arm.isRemoved()
            && arm instanceof ArmControl.Access access) {
            var availability = ArmControl.availability(arm, access);
            return nativeEnumOption(state, arm, id, ARM_SELECTION, ArmBlockEntity.SelectionMode.class,
                availability.equals("busy") || availability.equals("recovery_required"));
        }
        if (AllBlocks.DEPLOYER.has(state) && entity instanceof DeployerBlockEntity deployer
            && !deployer.isRemoved() && DeployerControl.supported()) {
            var value = new DataValue.TextValue(DeployerControl.modeValue(deployer));
            if (!DEPLOYER_MODE.format().validate(value).equals("ok")) return Optional.empty();
            return Optional.of(new NativeTarget(state, deployer, new Observation(id, DEPLOYER_MODE, value, DeployerControl.modeBusy(deployer)),
                update -> DeployerControl.setMode(deployer, ((DataValue.TextValue)update).value())));
        }
        if (AllBlocks.ROTATION_SPEED_CONTROLLER.has(state) && entity instanceof SpeedControllerBlockEntity controller
            && !controller.isRemoved() && controller.targetSpeed != null) {
            var speed = controller.targetSpeed;
            var range = (BiocomputingScrollRangeAccess)speed;
            var field = new Field("target_rpm", "rpm", new IntRange(range.createBiotech$min(), range.createBiotech$max()),
                "native_kinetic_reattach");
            return Optional.of(new NativeTarget(state, controller,
                new Observation(id, field, new DataValue.IntegerValue(speed.getValue()), false), value -> {
                    speed.setValue(integer(value));
                    return speed.getValue() == integer(value);
                }));
        }
        if (AllBlocks.WEIGHTED_EJECTOR.has(state) && entity instanceof EjectorBlockEntity ejector && !ejector.isRemoved()) {
            var size = BlockEntityBehaviour.get(ejector, ScrollValueBehaviour.TYPE);
            if (size == null || !size.isActive()) return Optional.empty();
            var bounds = (BiocomputingScrollRangeAccess)size;
            var field = new Field("batch_size", "item", new IntRange(bounds.createBiotech$min(), bounds.createBiotech$max()),
                "require_charged_ejector");
            return Optional.of(new NativeTarget(state, ejector, new Observation(id, field,
                new DataValue.IntegerValue(size.getValue()), ejector.getState() != EjectorBlockEntity.State.CHARGED), value -> {
                    size.setValue(integer(value));
                    return size.getValue() == integer(value);
                }));
        }
        if ((AllBlocks.LINEAR_CHASSIS.has(state) || AllBlocks.SECONDARY_LINEAR_CHASSIS.has(state)
                || AllBlocks.RADIAL_CHASSIS.has(state)) && entity instanceof ChassisBlockEntity chassis && !chassis.isRemoved()) {
            if (!(BlockEntityBehaviour.get(chassis, ScrollValueBehaviour.TYPE) instanceof BulkScrollValueBehaviour range)
                || !range.isActive()) return Optional.empty();
            var bounds = (BiocomputingScrollRangeAccess)range;
            var field = new Field("attachment_range", "block",
                new IntRange(bounds.createBiotech$min(), bounds.createBiotech$max()), "next_chassis_assembly");
            return Optional.of(new NativeTarget(state, chassis, new Observation(id, field,
                new DataValue.IntegerValue(chassis.getRange()), false), value -> {
                    // Create's scrollbar can bulk-edit the group; its single-target setter owns saving and sync.
                    range.setValue(integer(value));
                    return chassis.getRange() == integer(value);
                }));
        }
        if ((AllBlocks.COPPER_VALVE_HANDLE.has(state) || AllBlocks.DYED_VALVE_HANDLES.contains(state.getBlock()))
            && entity instanceof ValveHandleBlockEntity valve && !valve.isRemoved() && valve.angleInput != null) {
            var angle = valve.angleInput;
            var bounds = (BiocomputingScrollRangeAccess)angle;
            // Create's value board excludes zero even though its signed backing range spans zero.
            var field = new Field("turn_angle", "degree",
                new IntRange(bounds.createBiotech$min(), bounds.createBiotech$max(), false), "reject_active_cranking");
            return Optional.of(new NativeTarget(state, valve, new Observation(id, field,
                new DataValue.IntegerValue(angle.getValue()), !angle.isActive()), value -> {
                    angle.setValue(integer(value));
                    return angle.getValue() == integer(value);
                }));
        }
        if (state.is(Blocks.COMPARATOR) && entity instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity && !entity.isRemoved()) {
            return Optional.of(new NativeTarget(state, entity, new Observation(id, MODE,
                new DataValue.TextValue(state.getValue(ComparatorBlock.MODE).getSerializedName()), false), value -> {
                    var next = state.setValue(ComparatorBlock.MODE, ((DataValue.TextValue)value).value().equals("subtract")
                        ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE);
                    if (!level.setBlock(pos, next, Block.UPDATE_CLIENTS)) return false;
                    ((BiocomputingComparatorAccess) block).createBiotech$refreshOutputState(level, pos, next);
                    return true;
                }));
        }
        boolean timer = AllBlocks.PULSE_TIMER.has(state);
        if ((timer || AllBlocks.PULSE_REPEATER.has(state) || AllBlocks.PULSE_EXTENDER.has(state))
                && entity instanceof BrassDiodeBlockEntity diode && !diode.isRemoved()) {
            if (requestedField.equals(INVERTED.name())) {
                // Same property and neighbor notifications as BrassDiodeBlock's native interaction.
                return Optional.of(new NativeTarget(state, diode, new Observation(id, INVERTED,
                    new DataValue.BooleanValue(state.getValue(BrassDiodeBlock.INVERTED)), false), value ->
                        level.setBlock(pos, state.setValue(BrassDiodeBlock.INVERTED, ((DataValue.BooleanValue)value).value()), Block.UPDATE_ALL)));
            }
            var interval = BlockEntityBehaviour.get(diode, ScrollValueBehaviour.TYPE);
            if (interval == null) return Optional.empty();
            var limits = (BiocomputingScrollRangeAccess)interval;
            var field = new Field("interval", "game_tick", new IntRange(limits.createBiotech$min(), limits.createBiotech$max()),
                timer ? "native_clamp_progress" : "reject_active_pulse");
            var ticks = level.getBlockTicks();
            boolean busy = !timer && (!diode.isIdle() || state.getValue(BrassDiodeBlock.POWERING)
                || state.getValue(BrassDiodeBlock.POWERED)
                || ticks.hasScheduledTick(pos, block) || ticks.willTickThisTick(pos, block));
            return Optional.of(new NativeTarget(state, diode, new Observation(id, field, new DataValue.IntegerValue(interval.getValue()), busy), value -> {
                interval.setValue(integer(value));
                return interval.getValue() == integer(value);
            }));
        }
        if (AllBlocks.THRESHOLD_SWITCH.has(state) && entity instanceof ThresholdSwitchBlockEntity threshold && !entity.isRemoved()) {
            var observedPos = pos.relative(ThresholdSwitchBlock.getTargetDirection(state));
            // The native recompute may query a capability. Never let this output load an observed chunk or cross a sub-level.
            if (!level.shouldTickBlocksAt(observedPos) || !level.getWorldBorder().isWithinBounds(observedPos)
                || level.getChunkSource().getChunkNow(observedPos.getX() >> 4, observedPos.getZ() >> 4) == null
                || !com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.sameSpace(level, pos, observedPos)
                || threshold.getMinLevel() < 0 || threshold.getMaxLevel() <= threshold.getMinLevel()) return Optional.empty();
            var unit = switch (threshold.getTypeOfCurrentTarget()) {
                case ITEM -> "item";
                case FLUID -> "millibucket";
                case CUSTOM -> "native_unit";
                case UNSUPPORTED -> null;
            };
            if (unit == null) return Optional.empty();
            if (requestedField.equals(THRESHOLD_INVERTED.name())) {
                var ticks = level.getBlockTicks();
                // Create refreshes the output immediately when changing polarity. Do not advance a queued stock edge.
                boolean pending = ticks.hasScheduledTick(pos, block) || ticks.willTickThisTick(pos, block);
                return Optional.of(new NativeTarget(state, threshold, new Observation(id, THRESHOLD_INVERTED,
                    new DataValue.BooleanValue(threshold.isInverted()), pending), value -> {
                        var inverted = ((DataValue.BooleanValue)value).value();
                        threshold.setInverted(inverted);
                        threshold.setChanged();
                        return threshold.isInverted() == inverted;
                    }));
            }
            var field = new Field("thresholds", unit, new ThresholdPair(new IntRange(threshold.getMinLevel(), threshold.getMaxLevel())),
                "native_hysteresis");
            var value = new DataValue.RecordValue(Map.of("off_below", new DataValue.IntegerValue(threshold.offWhenBelow),
                "on_above", new DataValue.IntegerValue(threshold.onWhenAbove)));
            return Optional.of(new NativeTarget(state, threshold, new Observation(id, field, value, false), setting -> {
                var pair = (DataValue.RecordValue)setting;
                // Create's player configuration writes these two settings together. Recompute through its native measurement path.
                threshold.offWhenBelow = integer(pair.field("off_below"));
                threshold.onWhenAbove = integer(pair.field("on_above"));
                threshold.setChanged();
                threshold.updateCurrentLevel();
                threshold.sendData();
                return true;
            }));
        }
        if ((id.equals(AERO_BURNER) || id.equals(AERO_STEAM_VENT))
                && entity instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity heater && !heater.isRemoved()
                && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))) {
            var nativeControl = BlockEntityBehaviour.get(heater, ScrollValueBehaviour.TYPE);
            if (!(nativeControl instanceof DynamicScrollRange bounds) || !nativeControl.isActive()) return Optional.empty();
            int min = bounds.biocomputingMinimum(), max = bounds.biocomputingMaximum();
            if (max < min) return Optional.empty();
            var field = new Field("target_volume", "cubic_metre", new IntRange(min, max), "native_target_volume");
            return Optional.of(new NativeTarget(state, heater, new Observation(id, field,
                new DataValue.IntegerValue(nativeControl.getValue()), false), value -> {
                    // Only the native volume target; no redstone, actual gas, steam or fuel state.
                    nativeControl.setValue(integer(value));
                    return nativeControl.getValue() == integer(value);
                }));
        }
        if (entity == null || entity.isRemoved()) return Optional.empty();
        if (AllBlocks.ELEVATOR_CONTACT.has(state) && entity instanceof ElevatorContactBlockEntity contact
                && contact.columnCoords != null && contact.doorControls != null) {
            if (requestedField.equals(DOOR_CONTROL.name())) {
                var doors = contact.doorControls;
                return Optional.of(new NativeTarget(state, contact, new Observation(id, DOOR_CONTROL,
                    new DataValue.TextValue(DOOR_MODES.get(doors.mode.ordinal())), false), value -> {
                        var mode = DoorControl.valueOf(((DataValue.TextValue)value).value().toUpperCase(java.util.Locale.ROOT));
                        doors.set(mode);
                        return doors.mode == mode;
                    }));
            }
            var names = new DataValue.RecordValue(Map.of("short", new DataValue.TextValue(contact.shortName),
                "long", new DataValue.TextValue(contact.longName)));
            return Optional.of(new NativeTarget(state, contact, new Observation(id, FLOOR_NAMES, names, false), value -> {
                var newNames = (DataValue.RecordValue)value;
                var shortName = ((DataValue.TextValue)newNames.field("short")).value();
                var longName = ((DataValue.TextValue)newNames.field("long")).value();
                contact.updateName(shortName, longName);
                return contact.shortName.equals(shortName) && contact.longName.equals(longName);
            }));
        }
        if (AllBlocks.WINDMILL_BEARING.has(state) && entity instanceof WindmillBearingBlockEntity windmill)
            return nativeEnumOption(state, windmill, id, ROTATION_DIRECTION, RotationDirection.class, false);
        if (AllBlocks.STEAM_ENGINE.has(state) && entity instanceof SteamEngineBlockEntity engine && !engine.isRemoved()
                && BlockEntityBehaviour.get(engine, ScrollValueBehaviour.TYPE) instanceof ScrollOptionBehaviour<?> direction) {
            // Only inspect the native active option once the linked shaft position is safe to observe.
            // If externally driven, retain a readable setting but refuse changing it.
            boolean busy = steamShaftBusy(level, pos, state) || !direction.isActive();
            return nativeEnumOption(state, engine, id, ROTATION_DIRECTION, RotationDirection.class::equals, busy, direction);
        }
        if (AllBlocks.MECHANICAL_BEARING.has(state) && entity instanceof MechanicalBearingBlockEntity bearing)
            return nativeEnumOption(state, bearing, id, ROTARY_MOVEMENT_MODE, RotationMode.class, bearing.isRunning());
        if (AllBlocks.CART_ASSEMBLER.has(state) && entity instanceof CartAssemblerBlockEntity assembler && !assembler.isRemoved())
            // Create copies the mode into each new MountedContraption; changing the knob never edits an existing cart.
            return nativeEnumOption(state, assembler, id, CART_MOVEMENT_MODE, CartMovementMode.class, false);
        if (AllBlocks.MECHANICAL_ROLLER.has(state) && entity instanceof RollerBlockEntity roller && !roller.isRemoved())
            return nativeEnumOption(state, roller, id, ROLLER_MODE,
                type -> type.getDeclaringClass() == RollerBlockEntity.class && type.getSimpleName().equals("RollingMode"),
                !isolatedRoller(level, pos, state));
        if (AllBlocks.CLOCKWORK_BEARING.has(state) && entity instanceof ClockworkBearingBlockEntity clock && !clock.isRemoved())
            return nativeEnumOption(state, clock, id, CLOCK_HANDS,
                type -> type.getDeclaringClass() == ClockworkBearingBlockEntity.class && type.getSimpleName().equals("ClockHands"),
                clock.isRunning());
        // Elevator pulleys inherit the actuator base but have a fixed movement mode, not this player option.
        if ((AllBlocks.MECHANICAL_PISTON.has(state) || AllBlocks.STICKY_MECHANICAL_PISTON.has(state) || AllBlocks.ROPE_PULLEY.has(state))
                && entity instanceof LinearActuatorBlockEntity actuator)
            return nativeEnumOption(state, actuator, id, LINEAR_MOVEMENT_MODE, MovementMode.class,
                actuator.running || actuator.assembleNextTick || actuator.movedContraption != null);
        if (id.equals(SIM_VELOCITY_SENSOR) && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))
                && entity instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity sensor) {
            var scale = BlockEntityBehaviour.get(sensor, ScrollValueBehaviour.TYPE);
            if (scale == null || !scale.isActive()) return Optional.empty();
            var bounds = (BiocomputingScrollRangeAccess)scale;
            if (bounds.createBiotech$min() < 1 || bounds.createBiotech$max() < bounds.createBiotech$min()) return Optional.empty();
            var current = scale.getValueSettings();
            var choices = VELOCITY_DIRECTIONS.values();
            if (current.row() < 0 || current.row() >= choices.size()) return Optional.empty();
            var reading = new DataValue.RecordValue(Map.of("full_speed", new DataValue.IntegerValue(current.value()),
                "direction", new DataValue.TextValue(choices.get(current.row()))));
            var field = new Field("velocity_calibration", "metre_per_second",
                new VelocityCalibration(new IntRange(bounds.createBiotech$min(), bounds.createBiotech$max())), "native_velocity_calibration");
            return Optional.of(new NativeTarget(state, sensor, new Observation(id, field, reading, false), value -> {
                if (actor == null || !level.mayInteract(actor, pos)) return false;
                var pair = (DataValue.RecordValue)value;
                var next = new ValueSettings(choices.indexOf(((DataValue.TextValue)pair.field("direction")).value()),
                    integer(pair.field("full_speed")));
                scale.setValueSettings(actor, next, false);
                if (!next.equals(scale.getValueSettings())) return false;
                // Simulated updates the direction after the base setter returns. Persist and sync the pair,
                // including when only the direction changed and the base setter did not mark the block dirty.
                sensor.setChanged();
                sensor.sendData();
                return true;
            }));
        }
        if (id.equals(SIM_GIMBAL_SENSOR) && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))
                && entity instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity sensor) {
            var nativeSettings = BlockEntityBehaviour.get(sensor, ScrollValueBehaviour.TYPE);
            if (nativeSettings == null || !nativeSettings.isActive()) return Optional.empty();
            var bounds = (BiocomputingScrollRangeAccess)nativeSettings;
            var captured = gimbalAngles(level, nativeSettings);
            if (captured == null || bounds.createBiotech$max() < bounds.createBiotech$min()) return Optional.empty();
            var field = new Field("angle_calibration", "degree",
                new AnglePair(new IntRange(bounds.createBiotech$min(), bounds.createBiotech$max())), "native_angle_calibration");
            return Optional.of(new NativeTarget(state, sensor, new Observation(id, field, captured, false), value -> {
                var pair = (DataValue.RecordValue)value;
                // Build a fresh, fixed-shape native clipboard. Never hand player-provided NBT to the block.
                if (actor == null || !level.mayInteract(actor, pos)) return false;
                var calibration = new CompoundTag();
                calibration.putInt(GIMBAL_PRIMARY, integer(pair.field("primary")));
                calibration.putInt(GIMBAL_SECONDARY, integer(pair.field("secondary")));
                return nativeSettings.readFromClipboard(level.registryAccess(), calibration, actor, Direction.UP, false)
                    && pair.equals(gimbalAngles(level, nativeSettings));
            }));
        }
        if (id.equals(SIM_THROTTLE_LEVER) && block instanceof IWrenchable lever
                && entity instanceof SmartBlockEntity throttle && !throttle.isRemoved()
                && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(throttle.getType()))
                && block.getStateDefinition().getProperty(INVERTED.name()) instanceof BooleanProperty polarity) {
            return Optional.of(new NativeTarget(state, throttle,
                new Observation(id, INVERTED, new DataValue.BooleanValue(state.getValue(polarity)), false), value -> {
                    if (actor == null || !level.mayInteract(actor, pos)) return false;
                    var next = state.setValue(polarity, ((DataValue.BooleanValue)value).value());
                    // The native wrench action retains the handle position and updates its cached signal and neighbors.
                    var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                    var result = lever.onWrenched(state, new UseOnContext(actor, InteractionHand.MAIN_HAND, hit));
                    return result.consumesAction() && level.getBlockState(pos) == next;
                }));
        }
        boolean laser = id.equals(SIM_LASER_POINTER);
        if ((laser || id.equals(SIM_OPTICAL_SENSOR)) && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))
                && entity instanceof SmartBlockEntity rangeDevice) {
            if (laser && requestedField.equals(INVERTED.name())) {
                var polarity = BlockStateProperties.INVERTED;
                if (!state.hasProperty(polarity)) return Optional.empty();
                return Optional.of(new NativeTarget(state, rangeDevice,
                    new Observation(id, INVERTED, new DataValue.BooleanValue(state.getValue(polarity)), false), value -> {
                        var next = state.setValue(polarity, ((DataValue.BooleanValue)value).value());
                        // Match the native wrench action: update only polarity, and let the emitter process redstone itself.
                        KineticBlockEntity.switchToBlockState(level, pos, next);
                        return level.getBlockState(pos) == next;
                    }));
            }
            // These are the two known range controls; live power, colour and physical filters stay read-only.
            var range = BlockEntityBehaviour.get(rangeDevice, ScrollValueBehaviour.TYPE);
            if (range == null || !range.isActive()) return Optional.empty();
            var bounds = (BiocomputingScrollRangeAccess)range;
            if (bounds.createBiotech$max() < bounds.createBiotech$min()) return Optional.empty();
            var field = new Field(laser ? MAX_BEAM_RANGE : "max_detection_range", "block",
                new IntRange(bounds.createBiotech$min(), bounds.createBiotech$max()),
                laser ? "native_beam_range" : "native_detection_range");
            return Optional.of(new NativeTarget(state, rangeDevice,
                new Observation(id, field, new DataValue.IntegerValue(range.getValue()), false), value -> {
                    range.setValue(integer(value));
                    return range.getValue() == integer(value);
                }));
        }
        boolean steering = id.equals(SIM_STEERING_WHEEL) && entity instanceof GeneratingKineticBlockEntity;
        if ((steering || id.equals(SIM_TORSION_SPRING)) && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))
                && entity instanceof SmartBlockEntity angleDevice) {
            // Both known devices expose bounds, not an instruction to set the actual or requested angle.
            var angleLimit = BlockEntityBehaviour.get(angleDevice, ScrollValueBehaviour.TYPE);
            if (angleLimit == null || !angleLimit.isActive()) return Optional.empty();
            var limits = (BiocomputingScrollRangeAccess)angleLimit;
            if (limits.createBiotech$max() < limits.createBiotech$min()) return Optional.empty();
            boolean busy = steering && angleDevice instanceof GeneratingKineticBlockEntity wheel
                && (wheel.getGeneratedSpeed() != 0 || wheel.getSpeed() != 0);
            var field = new Field("travel_angle", "degree",
                new IntRange(limits.createBiotech$min(), limits.createBiotech$max()),
                steering ? "reject_active_steering" : "native_travel_limit");
            return Optional.of(new NativeTarget(state, angleDevice,
                new Observation(id, field, new DataValue.IntegerValue(angleLimit.getValue()), busy), value -> {
                    angleLimit.setValue(integer(value));
                    return angleLimit.getValue() == integer(value);
                }));
        }
        if ((id.equals(AERO_PROPELLER) || id.equals(AERO_GYRO_PROPELLER)) && entity instanceof MechanicalBearingBlockEntity bearing
                && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))) {
            if (!(BlockEntityBehaviour.get(bearing, ScrollValueBehaviour.TYPE) instanceof ScrollOptionBehaviour<?> direction)
                || !direction.isActive() || !"ThrustDirection".equals(direction.getClipboardKey())) return Optional.empty();
            var selected = direction.get();
            var requestedChoices = ((Choices)THRUST_DIRECTION.format()).values();
            var nativeChoices = java.util.Arrays.stream(selected.getDeclaringClass().getEnumConstants())
                .map(option -> option.name().toLowerCase(java.util.Locale.ROOT)).toList();
            if (!nativeChoices.equals(requestedChoices)) return Optional.empty();
            return Optional.of(new NativeTarget(state, bearing, new Observation(id, THRUST_DIRECTION,
                new DataValue.TextValue(nativeChoices.get(selected.ordinal())), false), value -> {
                    var next = nativeChoices.indexOf(((DataValue.TextValue)value).value());
                    direction.setValue(next); // Aero updates its generated rotation through the option's own callback.
                    return direction.getValue() == next;
                }));
        }
        if (entity instanceof GeneratingKineticBlockEntity engine
                && SIM_PORTABLE_ENGINE.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(engine.getType()))
                && engine.getType().isValid(state)) {
            // The native type registers every dyed block. Its option callback updates Create's driven network.
            return nativeEnumOption(state, engine, id, ROTATION_DIRECTION, RotationDirection.class, false);
        }
        if (id.equals(SIM_SWIVEL_BEARING) && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))
                && entity instanceof KineticBlockEntity swivel) {
            // Only change the native option. Simulated handles redstone, constraints and the current angle in its own tick.
            return nativeEnumOption(state, swivel, id, LOCKING_MODE, type -> SWIVEL_LOCKING_OPTION.equals(type.getName()), false);
        }
        if (id.equals(OFFROAD_WHEEL_MOUNT) && OFFROAD_WHEEL_MOUNT.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))
                && entity instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity wheelMount) {
            // Offroad's Wheel Mount registers exactly one scroll behavior: the suspension control used by the physics tick.
            var strength = BlockEntityBehaviour.get(wheelMount, ScrollValueBehaviour.TYPE);
            if (strength == null || !strength.isActive()) return Optional.empty();
            var limits = (BiocomputingScrollRangeAccess)strength;
            var field = new Field("suspension_strength", "native_unit", new IntRange(limits.createBiotech$min(), limits.createBiotech$max()),
                "native_suspension_tick");
            return Optional.of(new NativeTarget(state, wheelMount, new Observation(id, field, new DataValue.IntegerValue(strength.getValue()), false),
                value -> {
                    strength.setValue(integer(value));
                    return strength.getValue() == integer(value);
                }));
        }
        if (id.equals(SIM_ALTITUDE) && entity instanceof SimulatedSettingsAccess.Altitude altitude) {
            float zero = altitude.biocomputingZeroAltitude(), full = altitude.biocomputingFullAltitude();
            if (!Float.isFinite(zero) || !Float.isFinite(full)) return Optional.empty();
            var field = new Field("altitude_scale", "normalized_height", new AltitudePair(), "native_recalculate");
            var current = new DataValue.RecordValue(Map.of("zero_at", new DataValue.DecimalValue(zero), "full_at", new DataValue.DecimalValue(full)));
            return Optional.of(new NativeTarget(state, entity, new Observation(id, field, current, false), value -> {
                var pair = (DataValue.RecordValue)value;
                altitude.biocomputingSetAltitude((float)((DataValue.DecimalValue)pair.field("zero_at")).value(),
                    (float)((DataValue.DecimalValue)pair.field("full_at")).value());
                return true;
            }));
        }
        if (id.equals(SIM_RECEIVER) && entity instanceof SimulatedSettingsAccess.Receiver receiver) {
            int limit = receiver.biocomputingDistanceLimit();
            if (limit < 1) return Optional.empty();
            var field = new Field("distance_band", "block", new DistancePair(new IntRange(1, limit)), "native_receiver_tick");
            var current = new DataValue.RecordValue(Map.of("minimum", new DataValue.IntegerValue(receiver.biocomputingMinimumDistance()),
                "maximum", new DataValue.IntegerValue(receiver.biocomputingMaximumDistance())));
            return Optional.of(new NativeTarget(state, entity, new Observation(id, field, current, false), value -> {
                var pair = (DataValue.RecordValue)value;
                receiver.biocomputingSetDistances(integer(pair.field("minimum")), integer(pair.field("maximum")));
                return true;
            }));
        }
        if (id.equals(SIM_ACCUMULATOR) && entity instanceof SmartBlockEntity accumulator && !accumulator.isRemoved()
                && id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(accumulator.getType()))
                && accumulator instanceof ClipboardCloneable settings
                && BlockEntityBehaviour.get(accumulator, ScrollValueBehaviour.TYPE) instanceof BrassDiodeScrollValueBehaviour interval
                && interval.isActive()) {
            if (requestedField.equals(INVERTED.name())) {
                var property = BlockStateProperties.INVERTED;
                if (!state.hasProperty(property)) return Optional.empty();
                return Optional.of(new NativeTarget(state, accumulator,
                    new Observation(id, INVERTED, new DataValue.BooleanValue(state.getValue(property)), false), value -> {
                        var polarity = new CompoundTag();
                        boolean desired = ((DataValue.BooleanValue)value).value();
                        polarity.putBoolean(CLIPBOARD_INVERTED, desired);
                        // The native clipboard changes polarity and notifies neighbours without rewriting the accumulated count.
                        return settings.readFromClipboard(level.registryAccess(), polarity, actor, Direction.UP, false)
                            && level.getBlockState(pos).getOptionalValue(property).filter(actual -> actual == desired).isPresent();
                    }));
            }
            var range = (BiocomputingScrollRangeAccess)interval;
            var field = new Field("interval", "game_tick", new IntRange(range.createBiotech$min(), range.createBiotech$max()),
                "native_keep_progress");
            return Optional.of(new NativeTarget(state, accumulator,
                new Observation(id, field, new DataValue.IntegerValue(interval.getValue()), false), value -> {
                    // The accumulator keeps its current counter and applies its own timing on subsequent ticks.
                    interval.setValue(integer(value));
                    return interval.getValue() == integer(value);
                }));
        }
        if (id.equals(SIM_INDUCTOR) && entity instanceof SimulatedSettingsAccess.Inductor inductor && !entity.isRemoved()) {
            var delay = inductor.biocomputingInputDelay();
            if (delay == null) return Optional.empty();
            if (requestedField.equals(INVERTED.name())) {
                if (!(entity instanceof ClipboardCloneable settings)) return Optional.empty();
                return inductorInverted(level, settings).map(inverted -> new NativeTarget(state, entity,
                    new Observation(id, INVERTED, new DataValue.BooleanValue(inverted), false), value -> {
                        boolean desired = ((DataValue.BooleanValue)value).value();
                        var clipboard = new CompoundTag();
                        clipboard.putBoolean(CLIPBOARD_INVERTED, desired);
                        if (!settings.readFromClipboard(level.registryAccess(), clipboard, actor, Direction.UP, false)
                            || entity.isRemoved() || inductorInverted(level, settings).filter(current -> current == desired).isEmpty())
                            return false;
                        // The right-click path also notifies the outgoing redstone network after changing polarity.
                        inductor.biocomputingRefreshSignal();
                        return true;
                    }));
            }
            var limits = (BiocomputingScrollRangeAccess)delay;
            var field = new Field("interval", "game_tick", new IntRange(limits.createBiotech$min(), limits.createBiotech$max()),
                "native_keep_progress");
            return Optional.of(new NativeTarget(state, entity, new Observation(id, field, new DataValue.IntegerValue(delay.getValue()), false), value -> {
                delay.setValue(integer(value));
                return delay.getValue() == integer(value);
            }));
        }
        return Optional.empty();
    }
}
