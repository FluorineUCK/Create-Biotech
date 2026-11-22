package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.CapabilitySnapshotValues;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.SnapshotLimits;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import static com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsProtocol.*;

/** Selects among existing native points; does not edit placement configuration or handle inventory itself. */
public final class ArmControl {
    private ArmControl() {}

    public interface Access {
        List<ArmInteractionPoint> biocomputingInputs();
        List<ArmInteractionPoint> biocomputingOutputs();
        ArmBlockEntity.Phase biocomputingPhase();
        boolean biocomputingLocked();
        ItemStack biocomputingHeldCopy();
        boolean biocomputingAdmit(Route route, java.util.function.BooleanSupplier stillAuthorized);
        boolean biocomputingRouting();
        UUID biocomputingAction();
        String biocomputingRouteState();
    }

    public record Endpoint(int index, ArmInteractionPoint point, Object witness) {}
    public record Route(UUID id, GameProfile actor, Endpoint input, Endpoint output) {}
    private record PointRead(ArmInteractionPoint point, WeakReference<Object> placement) {}
    record Read(UUID token, List<PointRead> inputs, List<PointRead> outputs, UUID action, String availability) {}
    record Admission(String code, Route route) {}

    static Admission plan(ArmBlockEntity arm, ServerPlayer actor, Read read, DataValue.RecordValue args) {
        if (!(args.field("input") instanceof DataValue.IntegerValue input)
            || !(args.field("output") instanceof DataValue.IntegerValue output)) return new Admission("type_mismatch", null);
        if (!read.availability().equals("ok")) return new Admission(read.availability(), null);
        var nativeArm = (Access)arm;
        String availability = availability(arm, nativeArm);
        if (!availability.equals("ok")) return new Admission(availability, null);
        if (!java.util.Objects.equals(read.action(), nativeArm.biocomputingAction()))
            return new Admission("version_conflict", null);
        var inputs = nativeArm.biocomputingInputs();
        var outputs = nativeArm.biocomputingOutputs();
        if (input.value() < 0 || input.value() >= read.inputs().size()
            || output.value() < 0 || output.value() >= read.outputs().size()) return new Admission("unknown_point", null);
        int from = (int)input.value(), to = (int)output.value();
        var readSource = read.inputs().get(from);
        var readDestination = read.outputs().get(to);
        if (from >= inputs.size() || to >= outputs.size()
            || inputs.get(from) != readSource.point() || outputs.get(to) != readDestination.point())
            return new Admission("version_conflict", null);
        var source = readSource.point();
        var destination = readDestination.point();
        for (var point : List.of(source, destination)) {
            var state = pointStatus(arm, actor, point);
            if (!state.equals("ready")) return new Admission(state, null);
        }
        if (source.getPos().equals(destination.getPos())) return new Admission("invalid_route", null);
        var sourceEndpoint = endpoint(actor.serverLevel(), from, source);
        var destinationEndpoint = endpoint(actor.serverLevel(), to, destination);
        if (!matchesPlacement(sourceEndpoint, readSource) || !matchesPlacement(destinationEndpoint, readDestination))
            return new Admission("point_changed", null);
        return new Admission("ok", new Route(UUID.randomUUID(), actor.getGameProfile(), sourceEndpoint, destinationEndpoint));
    }

    static String availability(ArmBlockEntity arm, Access access) {
        if (access.biocomputingRouteState().equals("recovery_required")) return "recovery_required";
        if (access.biocomputingRouting() || !access.biocomputingHeldCopy().isEmpty()
            || access.biocomputingPhase() != ArmBlockEntity.Phase.SEARCH_INPUTS
                && access.biocomputingPhase() != ArmBlockEntity.Phase.DANCING) return "busy";
        if (arm.isOverStressed()) return "overstressed";
        if (arm.getSpeed() == 0) return "no_drive";
        return access.biocomputingLocked() ? "redstone_locked" : "ok";
    }

    private static boolean matchesPlacement(Endpoint selected, PointRead observed) {
        return selected.witness() != null && selected.witness() == observed.placement().get();
    }

    private static Endpoint endpoint(ServerLevel level, int index, ArmInteractionPoint point) {
        return new Endpoint(index, point, witness(level, point.getPos()));
    }

    private static Object witness(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        var entity = chunk.getBlockEntity(pos);
        return entity == null ? chunk.getBlockState(pos).getBlock() : entity;
    }

    public static String endpointStatus(ArmBlockEntity arm, Route route, Endpoint endpoint, List<ArmInteractionPoint> current) {
        if (endpoint.index() >= current.size() || current.get(endpoint.index()) != endpoint.point()) return "point_changed";
        var level = (ServerLevel)arm.getLevel();
        var actor = level.getServer().getPlayerList().getPlayer(route.actor().getId());
        if (actor == null || actor.serverLevel() != level) actor = FakePlayerFactory.get(level, route.actor());
        var state = pointStatus(arm, actor, endpoint.point());
        if (!state.equals("ready")) return state;
        return witness(level, endpoint.point().getPos()) == endpoint.witness() ? "ready" : "point_changed";
    }

    static DataValue.RecordValue summary(ArmBlockEntity arm) {
        var nativeArm = (Access)arm;
        var phase = nativeArm.biocomputingPhase();
        var transfer = nativeArm.biocomputingRouteState();
        String note;
        // Restoring power cannot recover a saved held stack; the operator needs the recovery instruction.
        if (transfer.equals("recovery_required")) note = transfer;
        else if (arm.isOverStressed()) note = "overstressed";
        else if (arm.getSpeed() == 0) note = "no_drive";
        else if (nativeArm.biocomputingLocked() && (phase == ArmBlockEntity.Phase.SEARCH_INPUTS || phase == ArmBlockEntity.Phase.DANCING))
            note = "redstone_locked";
        else if (nativeArm.biocomputingRouting()) note = transfer;
        else note = switch (phase) {
            case MOVE_TO_INPUT, MOVE_TO_OUTPUT -> "moving";
            case SEARCH_OUTPUTS -> "waiting_for_output";
            case SEARCH_INPUTS, DANCING -> nativeArm.biocomputingInputs().isEmpty() || nativeArm.biocomputingOutputs().isEmpty()
                ? "no_configured_route" : "looking_for_input";
        };
        var id = nativeArm.biocomputingAction();
        return record(Map.of("phase", text(phase.name().toLowerCase(Locale.ROOT)), "reason", text(note),
            "input_count", number(nativeArm.biocomputingInputs().size()), "output_count", number(nativeArm.biocomputingOutputs().size()),
            "transfer", text(transfer), "action", id == null ? DataValue.NullValue.INSTANCE : text(id.toString())));
    }

    static Read read(ArmBlockEntity arm, ServerPlayer actor, String availability) {
        var limits = CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot();
        var access = (Access)arm;
        var inputs = access.biocomputingInputs();
        var outputs = access.biocomputingOutputs();
        if ((long)inputs.size() + outputs.size() > limits.maxEntries() / 6L) return null;
        if (!availability.equals("ok"))
            return new Read(UUID.randomUUID(), List.of(), List.of(), access.biocomputingAction(), availability);
        return new Read(UUID.randomUUID(), observePoints(arm, actor, inputs), observePoints(arm, actor, outputs),
            access.biocomputingAction(), availability);
    }

    private static List<PointRead> observePoints(ArmBlockEntity arm, ServerPlayer actor, List<ArmInteractionPoint> points) {
        var level = (ServerLevel)arm.getLevel();
        return points.stream().map(point -> {
            // The nonce witnesses a ready placement at read time without retaining unloaded block entities.
            Object observed = pointStatus(arm, actor, point).equals("ready") ? witness(level, point.getPos()) : null;
            return new PointRead(point, new WeakReference<>(observed));
        }).toList();
    }

    static DataValue.RecordValue snapshot(ArmBlockEntity arm, ServerPlayer actor) {
        var limits = CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot();
        var nativeArm = (Access)arm;
        var inputs = nativeArm.biocomputingInputs();
        var outputs = nativeArm.biocomputingOutputs();
        // Each complete descriptor costs at least six nodes. Reject before visiting arbitrary numbers of points.
        if ((long)inputs.size() + outputs.size() > limits.maxEntries() / 6L) return record(Map.of());
        var held = nativeArm.biocomputingHeldCopy();
        var values = new java.util.LinkedHashMap<>(summary(arm).fields());
        values.put("held", held.isEmpty() ? DataValue.NullValue.INSTANCE
            : CapabilitySnapshotValues.itemDescription(held, actor.registryAccess(), limits));
        values.put("inputs", points(arm, actor, inputs));
        values.put("outputs", points(arm, actor, outputs));
        var result = record(values);
        SnapshotLimits.validate(result, limits);
        return result;
    }

    /** Only the common native placement fields, never arbitrary point-provider or block entity NBT. */
    static DataValue.NbtValue nativePoints(ArmBlockEntity arm, ServerPlayer actor) {
        var config = CBConfigs.SERVER.biocomputing;
        boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
        var read = config.peripherals(loaded).snapshot();
        var wire = config.serialCodec(loaded);
        var limit = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Limits(
            wire.maxEncodedBytes(), wire.maxDecodeHeapBytes(),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(
                read.maxEntries(), read.maxStringChars(), read.maxValueUnits()));
        var access = (Access)arm;
        var inputs = access.biocomputingInputs();
        var outputs = access.biocomputingOutputs();
        var level = (ServerLevel)arm.getLevel();
        var anchor = arm.getBlockPos();
        if ((long)inputs.size() + outputs.size() > Math.max(0, (read.maxEntries() - 2) / 7))
            throw new IllegalArgumentException("snapshot_limit");
        var points = new net.minecraft.nbt.ListTag();
        for (var group : List.of(inputs, outputs)) for (var point : group) {
            var pos = point.getPos();
            if (!pos.closerThan(anchor, ArmBlockEntity.getRange()) || !level.getWorldBorder().isWithinBounds(pos)
                || !actor.mayBuild() || actor.isSpectator() || !level.mayInteract(actor, anchor) || !level.mayInteract(actor, pos))
                throw new IllegalArgumentException("access_denied");
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null)
                throw new IllegalArgumentException("point_unloaded");
            if (!SubLevelCompat.sameSpace(level, anchor, pos)) throw new IllegalArgumentException("point_other_space");
            var key = CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(point.getType());
            if (key == null) throw new IllegalArgumentException("device_error");
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("Type", key.toString());
            tag.put("Pos", net.minecraft.nbt.NbtUtils.writeBlockPos(pos.subtract(anchor)));
            net.createmod.catnip.nbt.NBTHelper.writeEnum(tag, "Mode", point.getMode());
            points.add(tag);
        }
        var copy = new net.minecraft.nbt.CompoundTag();
        copy.put("InteractionPoints", points);
        return com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.snapshot(copy, limit);
    }

    private static DataValue.ListValue points(ArmBlockEntity arm, ServerPlayer actor, List<ArmInteractionPoint> points) {
        var level = (ServerLevel)arm.getLevel();
        var anchor = arm.getBlockPos();
        var values = new ArrayList<DataValue>(points.size());
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            var pos = point.getPos();
            if (!pos.closerThan(anchor, ArmBlockEntity.getRange()) || !level.getWorldBorder().isWithinBounds(pos)) {
                values.add(record(Map.of("index", number(i), "status", text("point_out_of_range"))));
                continue;
            }
            if (!actor.serverLevel().mayInteract(actor, pos)) {
                values.add(record(Map.of("index", number(i), "status", text("access_denied"))));
                continue;
            }
            var offset = pos.subtract(anchor);
            var kind = CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(point.getType());
            values.add(record(Map.of("index", number(i), "offset", offset(offset),
                "type", kind == null ? DataValue.NullValue.INSTANCE : text(kind.toString()),
                "status", text(pointStatus(arm, actor, point)))));
        }
        return new DataValue.ListValue(values);
    }

    private static DataValue.RecordValue offset(BlockPos offset) {
        return record(Map.of("x", number(offset.getX()), "y", number(offset.getY()), "z", number(offset.getZ())));
    }

    private static String pointStatus(ArmBlockEntity arm, ServerPlayer actor, ArmInteractionPoint point) {
        var level = (ServerLevel)arm.getLevel();
        var anchor = arm.getBlockPos();
        var pos = point.getPos();
        if (!pos.closerThan(anchor, ArmBlockEntity.getRange()) || !level.getWorldBorder().isWithinBounds(pos)) return "point_out_of_range";
        if (!actor.mayBuild() || actor.isSpectator() || !level.mayInteract(actor, arm.getBlockPos()) || !level.mayInteract(actor, pos))
            return "access_denied";
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !level.shouldTickBlocksAt(pos)) return "point_unloaded";
        if (!SubLevelCompat.sameSpace(level, anchor, pos)) return "point_other_space";
        var state = chunk.getBlockState(pos);
        if (state.isAir()) return "point_missing";
        return point.getType().canCreatePoint(level, pos, state) ? "ready" : "point_changed";
    }
}
