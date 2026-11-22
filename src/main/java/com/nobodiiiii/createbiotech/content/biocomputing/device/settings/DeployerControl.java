package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.CapabilitySnapshotValues;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import static com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsProtocol.*;

/** An explicit one-shot permit for the native transport callback only. */
public final class DeployerControl {
    private DeployerControl() {}

    // Create declares these two enum types package-private. Only read these exact native fields;
    // this is not a reflective player API and there is no reflective setter.
    private static final java.lang.reflect.Field STATE = nativeEnum("state");
    private static final java.lang.reflect.Field MODE = nativeEnum("mode");

    private static java.lang.reflect.Field nativeEnum(String name) {
        try {
            var field = DeployerBlockEntity.class.getDeclaredField(name);
            return field.getType().isEnum() && field.trySetAccessible() ? field : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
    static boolean supported() { return STATE != null && MODE != null; }
    public static String nativeState(DeployerBlockEntity deployer) { return enumName(STATE, deployer); }
    public static String nativeMode(DeployerBlockEntity deployer) { return enumName(MODE, deployer); }
    private static String enumName(java.lang.reflect.Field field, DeployerBlockEntity device) {
        if (field == null) return "unavailable";
        try { return field.get(device) instanceof Enum<?> value ? value.name() : "unavailable"; }
        catch (IllegalAccessException unavailable) { return "unavailable"; }
    }

    public interface Access {
        boolean biocomputingLocked();
        boolean biocomputingIdle();
        boolean biocomputingUseMode();
        long biocomputingModeRevision();
        boolean biocomputingOverflow();
        boolean biocomputingToolAllowed();
        long biocomputingFilterRevision();
        default boolean biocomputingConfigurationBusy() { return biocomputingSuppressWorld() || !biocomputingIdle(); }
        String biocomputingPhase();
        ItemStack biocomputingFilter();
        UUID biocomputingProcessId();
        String biocomputingProcess();
        boolean biocomputingAdmit(UUID action, TransportedItemStackHandlerBehaviour target, BooleanSupplier authorized);
        boolean biocomputingMayQueueStart(TransportedItemStackHandlerBehaviour target);
        boolean biocomputingMayOpen(TransportedItemStackHandlerBehaviour target);
        boolean biocomputingSuppressWorld();
        void biocomputingTransportStart(boolean value);
        void biocomputingCapture(TransportedItemStack stack, TransportedItemStackHandlerBehaviour target);
        boolean biocomputingMayConvert(TransportedItemStack stack, TransportedItemStackHandlerBehaviour target);
        void biocomputingConverted();
    }

    static boolean modeBusy(DeployerBlockEntity deployer) {
        return ((Access)deployer).biocomputingConfigurationBusy();
    }

    static String modeWriteStatus(DeployerBlockEntity deployer) {
        if (modeBusy(deployer)) return "busy";
        return ((Access)deployer).biocomputingLocked() && deployer.getLevel().hasNeighborSignal(deployer.getBlockPos())
            ? "ok" : "physical_lock_required";
    }

    static String modeValue(DeployerBlockEntity deployer) {
        return nativeMode(deployer).toLowerCase(Locale.ROOT);
    }

    static boolean setMode(DeployerBlockEntity deployer, String value) {
        if (!modeWriteStatus(deployer).equals("ok")) return false;
        deployer.changeMode(); // Two native modes; ComponentSettings already checked the choice and current value.
        return modeValue(deployer).equals(value);
    }

    static TransportedItemStackHandlerBehaviour target(DeployerBlockEntity deployer, ServerPlayer actor) {
        var level = (ServerLevel)deployer.getLevel();
        var pos = deployer.getBlockPos();
        var state = deployer.getBlockState();
        if (!AllBlocks.DEPLOYER.has(state) || state.getValue(DirectionalKineticBlock.FACING) != Direction.DOWN) return null;
        for (int depth = 1; depth <= 2; depth++) {
            var check = pos.below(depth);
            if (!level.getWorldBorder().isWithinBounds(check) || !level.shouldTickBlocksAt(check)
                || level.getChunkSource().getChunkNow(check.getX() >> 4, check.getZ() >> 4) == null
                || !SubLevelCompat.sameSpace(level, pos, check) || actor != null && !level.mayInteract(actor, check)) return null;
        }
        var material = level.getBlockState(pos.below(2));
        if (!AllBlocks.BELT.has(material) && !AllBlocks.DEPOT.has(material)) return null;
        return BlockEntityBehaviour.get(level, pos.below(2), TransportedItemStackHandlerBehaviour.TYPE);
    }

    static String readiness(DeployerBlockEntity deployer, TransportedItemStackHandlerBehaviour worksite) {
        var access = (Access)deployer;
        if (access.biocomputingSuppressWorld() || !access.biocomputingIdle()) return "busy";
        if (!access.biocomputingUseMode() || worksite == null) return "processing_target_required";
        if (!access.biocomputingLocked() || !deployer.getLevel().hasNeighborSignal(deployer.getBlockPos())) return "physical_lock_required";
        if (deployer.isOverStressed()) return "overstressed";
        if (deployer.getSpeed() == 0) return "no_drive";
        if (access.biocomputingOverflow()) return "output_full";
        var player = deployer.getPlayer();
        if (player == null || player.getMainHandItem().isEmpty()) return "tool_missing";
        if (!access.biocomputingToolAllowed()) return "tool_filtered";
        return "ok";
    }

    static DataValue.RecordValue summary(DeployerBlockEntity deployer) {
        var worksite = target(deployer, null);
        return summary(deployer, worksite, readiness(deployer, worksite));
    }

    private static DataValue.RecordValue summary(DeployerBlockEntity deployer, TransportedItemStackHandlerBehaviour worksite,
            String availability) {
        var access = (Access)deployer;
        var id = access.biocomputingProcessId();
        return record(Map.of("phase", text(access.biocomputingPhase()), "mode", text(access.biocomputingUseMode() ? "use" : "punch"),
            "scope", text(worksite != null ? "belt_processing" : "world_interaction_not_authorized"),
            "availability", text(availability), "process", text(access.biocomputingProcess()),
            "locked", new DataValue.BooleanValue(access.biocomputingLocked()),
            "action", id == null ? DataValue.NullValue.INSTANCE : text(id.toString())));
    }

    static DataValue.RecordValue snapshot(DeployerBlockEntity deployer, ServerPlayer actor,
            TransportedItemStackHandlerBehaviour worksite, String availability, UUID token, ItemStack held, ItemStack filter) {
        var limits = CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot();
        var values = new LinkedHashMap<>(summary(deployer, worksite, availability).fields());
        values.put("held", held.isEmpty() ? DataValue.NullValue.INSTANCE
            : CapabilitySnapshotValues.itemDescription(held.copy(), actor.registryAccess(), limits));
        values.put("filter", filter.isEmpty() ? DataValue.NullValue.INSTANCE
            : CapabilitySnapshotValues.itemDescription(filter, actor.registryAccess(), limits));
        values.put("token", text(token.toString()));
        return record(values);
    }
}
