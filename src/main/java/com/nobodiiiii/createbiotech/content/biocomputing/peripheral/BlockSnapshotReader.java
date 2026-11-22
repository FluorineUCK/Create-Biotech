package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Coordinates authorized, capture-scoped reads; machine-specific logic lives in providers. */
public final class BlockSnapshotReader {
    private BlockSnapshotReader() {}

    /** Compatibility snapshot. Opt-in item tags and pipe telemetry require explicit component selection. */
    public static DataValue.RecordValue read(ServerLevel level, BlockPos target, Direction targetFace,
                                             ServerPlayer actor, ReadLimits limits) {
        var components = new java.util.LinkedHashSet<>(ReadComponents.registry().ids());
        components.remove(BuiltinReadComponents.INVENTORY_ITEM_TAGS);
        components.remove(CreateFluidReadComponent.ID);
        return capture(level, target, targetFace, actor, limits, components, true);
    }

    /** Trusted backend entry point; player packets never supply arbitrary target coordinates. */
    public static DataValue.RecordValue readComponents(ServerLevel level, BlockPos target, Direction targetFace,
            ServerPlayer actor, ReadLimits limits, Set<String> componentIds) {
        return capture(level, target, targetFace, actor, limits, componentIds, false);
    }

    /** One fresh, guarded range capture. No whole-component cache or persistent paging state. */
    public static DataValue.RecordValue readRange(ServerLevel level, BlockPos target, Direction face,
            ServerPlayer actor, ReadLimits limits, String component, ReadRange range) {
        if (!range.fits(limits)) return failure("snapshot_limit");
        return capture(level, target, face, actor, limits, session -> {
            var value = session.readRange(component, range);
            if (value instanceof DataValue.ErrorValue error) return new DataValue.RecordValue(Map.of("error", error));
            var page = (DataValue.RecordValue)value;
            return new DataValue.RecordValue(Map.of("components", new DataValue.RecordValue(Map.of(component, page.field("items"))),
                "range", ReadRange.metadata(page)));
        }, false);
    }
    private static DataValue.RecordValue capture(ServerLevel level, BlockPos target, Direction face,
            ServerPlayer actor, ReadLimits limits, Set<String> componentIds, boolean legacy) {
        return capture(level, target, face, actor, limits, session -> session.read(componentIds), legacy);
    }
    private static DataValue.RecordValue capture(ServerLevel level, BlockPos target, Direction face,
            ServerPlayer actor, ReadLimits limits, java.util.function.Function<ReadComponentRegistry.Session<BlockReadContext>, DataValue.RecordValue> read, boolean legacy) {
        try {
            var checked = authorizeTarget(level,target,face,actor,limits);
            if (checked.error() != null) return new DataValue.RecordValue(Map.of("error",checked.error()));
            var values = read.apply(ReadComponents.registry().session(checked.context(), limits));
            var result = legacy ? LegacyBlockSnapshotView.of(values) : values;
            SnapshotLimits.validate(result, limits);
            return result;
        } catch (SnapshotLimits.LimitExceeded exceeded) {
            return failure("snapshot_limit");
        } catch (RuntimeException | StackOverflowError failure) {
            return failure("provider_failure");
        }
    }

    /** The same loaded-target/actor/policy check as a real sample, without invoking any provider. */
    static boolean mayReadTarget(ServerLevel level, BlockPos target, Direction face, ServerPlayer actor, ReadLimits limits) {
        try { return authorizeTarget(level,target,face,actor,limits).error() == null; }
        catch (RuntimeException | StackOverflowError failure) { return false; }
    }

    private record TargetCheck(BlockReadContext context, DataValue.ErrorValue error) {}
    private static TargetCheck authorizeTarget(ServerLevel level, BlockPos target, Direction face, ServerPlayer actor, ReadLimits limits) {
        // No block, BE, capability or serializer access before these guards.
        if (!level.isLoaded(target)) return new TargetCheck(null,CapabilitySnapshotValues.error("target_unloaded"));
        if (actor.serverLevel() != level || !level.getWorldBorder().isWithinBounds(target) || !level.mayInteract(actor,target))
            return new TargetCheck(null,CapabilitySnapshotValues.error("access_denied"));
        var state = level.getBlockState(target);
        var entity = level.getBlockEntity(target);
        if (entity instanceof ClusterControllerBlockEntity
            || entity instanceof SmartBlockEntity smart && (smart.getBehaviour(ComputerBehaviour.TYPE) != null
                || smart.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceBehaviour.TYPE) != null))
            return new TargetCheck(null,CapabilitySnapshotValues.error("access_denied"));
        var context = new BlockReadContext(level,target,face,actor,state,entity,limits,
            CBConfigs.SERVER.biocomputing.serializedBlockDataAllowed(CBConfigs.SERVER_SPEC.isLoaded()));
        for (ReadAccessPolicy policy : ReadComponents.policies()) {
            var denied = policy.deny(context);
            if (denied.isPresent()) return new TargetCheck(null,denied.orElseThrow());
        }
        return new TargetCheck(context,null);
    }

    public static DataValue.RecordValue position(BlockPos position) {
        return new DataValue.RecordValue(Map.of("x", CapabilitySnapshotValues.integer(position.getX()),
            "y", CapabilitySnapshotValues.integer(position.getY()), "z", CapabilitySnapshotValues.integer(position.getZ())));
    }

    private static DataValue.RecordValue failure(String code) {
        return new DataValue.RecordValue(Map.of("error", CapabilitySnapshotValues.error(code)));
    }
}
