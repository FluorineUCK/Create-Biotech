package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.DeployerControl;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ManagedDeployerWorldTicks;
import com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterTarget;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DeployerBlockEntity.class, remap = false)
public abstract class BiocomputingDeployerAccess implements DeployerControl.Access {
    @Shadow protected boolean redstoneLocked;
    @Shadow protected FilteringBehaviour filtering;
    @Shadow protected List<ItemStack> overflowItems;
    @Unique private static final String CREATE_BIOTECH_PROCESS = "CreateBiotechDeployerProcess";
    @Unique private UUID createBiotech$action;
    @Unique private TransportedItemStackHandlerBehaviour createBiotech$target;
    @Unique private BooleanSupplier createBiotech$authorized;
    @Unique private TransportedItemStack createBiotech$material;
    @Unique private ItemStack createBiotech$tool = ItemStack.EMPTY;
    @Unique private ItemStack createBiotech$filter = ItemStack.EMPTY;
    @Unique private long createBiotech$filterRevision;
    @Unique private boolean createBiotech$suppress;
    @Unique private String createBiotech$process = "idle";
    // Grants are per loaded placement. Track native toggles too, including a toggle followed by its inverse.
    @Unique private long createBiotech$modeRevision;

    @Override public boolean biocomputingLocked() { return redstoneLocked; }
    @Override public boolean biocomputingIdle() { return "waiting".equals(biocomputingPhase()); }
    @Override public boolean biocomputingUseMode() { return "USE".equals(DeployerControl.nativeMode(createBiotech$self())); }
    @Override public long biocomputingModeRevision() { return createBiotech$modeRevision; }
    @Override public boolean biocomputingOverflow() { return !overflowItems.isEmpty(); }
    @Override public boolean biocomputingToolAllowed() {
        var player = createBiotech$self().getPlayer();
        return player != null && !player.getMainHandItem().isEmpty() && filtering.test(player.getMainHandItem());
    }
    @Override public String biocomputingPhase() { return DeployerControl.nativeState(createBiotech$self()).toLowerCase(Locale.ROOT); }
    @Override public ItemStack biocomputingFilter() { return filtering.getFilter().copy(); }
    @Override public long biocomputingFilterRevision() { return VirtualFilterTarget.configurationRevision(filtering); }
    @Override public UUID biocomputingProcessId() { return createBiotech$action; }
    @Override public String biocomputingProcess() { return createBiotech$process; }
    @Override public boolean biocomputingSuppressWorld() { return createBiotech$suppress; }
    @Override public boolean biocomputingAdmit(UUID action, TransportedItemStackHandlerBehaviour target, BooleanSupplier authorized) {
        if (target == null || createBiotech$suppress || !biocomputingIdle() || !redstoneLocked || !biocomputingToolAllowed()) return false;
        createBiotech$action = action;
        createBiotech$target = target;
        createBiotech$authorized = authorized;
        createBiotech$tool = createBiotech$self().getPlayer().getMainHandItem().copy();
        createBiotech$filter = filtering.getFilter().copy();
        createBiotech$filterRevision = biocomputingFilterRevision();
        createBiotech$suppress = true;
        createBiotech$process = "waiting_for_material";
        createBiotech$self().setChanged();
        return true;
    }

    @Override public boolean biocomputingMayQueueStart(TransportedItemStackHandlerBehaviour target) {
        return createBiotech$eligible(target) && biocomputingIdle() && createBiotech$material == null;
    }
    @Override public boolean biocomputingMayOpen(TransportedItemStackHandlerBehaviour target) {
        return biocomputingMayQueueStart(target) && createBiotech$live(target);
    }
    @Override public void biocomputingCapture(TransportedItemStack stack, TransportedItemStackHandlerBehaviour target) {
        if (!biocomputingIdle() && createBiotech$material == null && createBiotech$target == target) {
            createBiotech$material = stack;
            createBiotech$process = "processing";
            createBiotech$self().setChanged();
        }
    }
    @Override public boolean biocomputingMayConvert(TransportedItemStack stack, TransportedItemStackHandlerBehaviour target) {
        if (!createBiotech$suppress) return true;
        if (!createBiotech$live(target) || createBiotech$material != stack || !createBiotech$process.equals("processing")) return false;
        createBiotech$process = "outcome_unknown";
        createBiotech$self().setChanged();
        return true;
    }
    @Override public void biocomputingConverted() {
        if (createBiotech$suppress && createBiotech$process.equals("outcome_unknown")) {
            createBiotech$process = "processed";
            createBiotech$self().setChanged();
        }
    }

    @Inject(method = "activate", at = @At("HEAD"), cancellable = true)
    private void createBiotech$neverActivateWorld(CallbackInfo ci) {
        if (createBiotech$suppress) ci.cancel();
    }
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void createBiotech$onlyStartNativeTransport(CallbackInfo ci) {
        if (createBiotech$suppress && !createBiotech$transportStart) ci.cancel();
    }
    @Unique private boolean createBiotech$transportStart;
    @Override public void biocomputingTransportStart(boolean value) { createBiotech$transportStart = value; }

    @Inject(method = "tick", at = @At("RETURN"))
    private void createBiotech$trackRecovery(CallbackInfo ci) {
        if (!createBiotech$suppress || createBiotech$self().getLevel().isClientSide) return;
        ManagedDeployerWorldTicks.observe(createBiotech$self(), this::createBiotech$updateReservation);
    }
    @Unique private void createBiotech$updateReservation() {
        if (!createBiotech$suppress) return;
        if (createBiotech$process.equals("waiting_for_material")) {
            if (!createBiotech$live(createBiotech$target)) createBiotech$stop("interrupted");
            return;
        }
        if (!biocomputingIdle()) return;
        createBiotech$stop(switch (createBiotech$process) {
            case "processed", "outcome_unknown", "interrupted" -> createBiotech$process;
            default -> "not_processed";
        });
    }
    @Inject(method = "changeMode", at = @At("HEAD"), cancellable = true)
    private void createBiotech$protectActiveMode(CallbackInfo ci) {
        if (createBiotech$suppress) ci.cancel();
    }
    @Inject(method = "changeMode", at = @At("TAIL"))
    private void createBiotech$trackModeChange(CallbackInfo ci) {
        createBiotech$modeRevision++;
    }
    @Inject(method = "write", at = @At("RETURN"))
    private void createBiotech$saveProcess(CompoundTag tag, HolderLookup.Provider lookup, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket || createBiotech$action == null) return;
        var process = new CompoundTag();
        process.putUUID("Id", createBiotech$action);
        process.putString("State", createBiotech$process);
        process.putBoolean("Active", createBiotech$suppress);
        tag.put(CREATE_BIOTECH_PROCESS, process);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void createBiotech$loadProcess(CompoundTag tag, HolderLookup.Provider lookup, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        var process = tag.getCompound(CREATE_BIOTECH_PROCESS);
        createBiotech$action = process.hasUUID("Id") ? process.getUUID("Id") : null;
        createBiotech$target = null;
        createBiotech$authorized = null;
        createBiotech$material = null;
        createBiotech$suppress = createBiotech$action != null && process.getBoolean("Active") && !biocomputingIdle();
        createBiotech$process = createBiotech$action == null ? "idle" : switch (process.getString("State")) {
            case "processed", "outcome_unknown", "not_processed", "interrupted" -> process.getString("State");
            default -> "interrupted";
        };
        // Keep the native return animation, but suppress both general activation and conversion until idle.
    }
    @Unique private boolean createBiotech$eligible(TransportedItemStackHandlerBehaviour target) {
        return target != null && createBiotech$target == target && createBiotech$authorized != null
            && redstoneLocked && biocomputingUseMode() && biocomputingToolAllowed()
            && createBiotech$filterRevision == biocomputingFilterRevision()
            && ItemStack.matches(createBiotech$tool, createBiotech$self().getPlayer().getMainHandItem())
            && ItemStack.matches(createBiotech$filter, filtering.getFilter());
    }
    @Unique private boolean createBiotech$live(TransportedItemStackHandlerBehaviour target) {
        if (!createBiotech$eligible(target)) return false;
        try { return createBiotech$authorized.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }
    @Unique private void createBiotech$stop(String result) {
        createBiotech$target = null;
        createBiotech$authorized = null;
        createBiotech$material = null;
        createBiotech$tool = ItemStack.EMPTY;
        createBiotech$filter = ItemStack.EMPTY;
        createBiotech$suppress = false;
        createBiotech$process = result;
        createBiotech$self().setChanged();
    }
    @Unique private DeployerBlockEntity createBiotech$self() { return (DeployerBlockEntity)(Object)this; }
}
