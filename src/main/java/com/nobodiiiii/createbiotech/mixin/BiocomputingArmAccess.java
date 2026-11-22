package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ArmControl;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ManagedArmWorldTicks;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ArmBlockEntity.class, remap = false)
public abstract class BiocomputingArmAccess implements ArmControl.Access {
    @Shadow List<ArmInteractionPoint> inputs;
    @Shadow List<ArmInteractionPoint> outputs;
    @Shadow ItemStack heldItem;
    @Shadow ArmBlockEntity.Phase phase;
    @Shadow protected boolean redstoneLocked;
    @Shadow float chasedPointProgress;
    @Shadow int chasedPointIndex;
    @Shadow protected abstract int getDistributableAmount(ArmInteractionPoint point, int slot);
    @Invoker("selectIndex") protected abstract void biocomputingSelectIndex(boolean input, int index);
    @Unique private ArmControl.Route createBiotech$route;
    @Unique private BooleanSupplier createBiotech$routeStillAuthorized;
    @Unique private UUID createBiotech$action;
    @Unique private String createBiotech$transfer = "idle";
    @Unique private boolean createBiotech$recovery;
    @Unique private boolean createBiotech$postTick;
    @Unique private static final String CREATE_BIOTECH_ROUTE = "CreateBiotechArmRoute";

    @Override public List<ArmInteractionPoint> biocomputingInputs() { return Collections.unmodifiableList(inputs); }
    @Override public List<ArmInteractionPoint> biocomputingOutputs() { return Collections.unmodifiableList(outputs); }
    @Override public ArmBlockEntity.Phase biocomputingPhase() { return phase; }
    @Override public boolean biocomputingLocked() { return redstoneLocked; }
    @Override public ItemStack biocomputingHeldCopy() { return heldItem.copy(); }
    @Override public UUID biocomputingAction() { return createBiotech$action; }
    @Override public boolean biocomputingRouting() { return createBiotech$managed(); }
    @Override public String biocomputingRouteState() { return createBiotech$transfer; }
    @Override public boolean biocomputingAdmit(ArmControl.Route route, BooleanSupplier stillAuthorized) {
        if (createBiotech$route != null || createBiotech$recovery || !heldItem.isEmpty()
            || phase != ArmBlockEntity.Phase.SEARCH_INPUTS && phase != ArmBlockEntity.Phase.DANCING) return false;
        createBiotech$route = route;
        createBiotech$routeStillAuthorized = stillAuthorized;
        createBiotech$action = route.id();
        createBiotech$transfer = "routing";
        createBiotech$arm().setChanged();
        return true;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void createBiotech$deferAuthorizedNativeTick(CallbackInfo ci) {
        var route = createBiotech$route;
        var arm = createBiotech$arm();
        if (route == null || createBiotech$postTick || !(arm.getLevel() instanceof ServerLevel level)) return;
        // Do not interpret the controller being later in the block-entity order as a revocation.
        // The existing checks in the native route hooks still run immediately before effects.
        ManagedArmWorldTicks.enqueue(level, arm, () -> {
            if (createBiotech$route != route) return;
            createBiotech$postTick = true;
            try { arm.tick(); }
            finally { createBiotech$postTick = false; }
        });
        ci.cancel();
    }

    @Inject(method = "searchForItem", at = @At("HEAD"), cancellable = true)
    private void createBiotech$chooseInput(CallbackInfo ci) {
        if (!createBiotech$managed()) return;
        ci.cancel();
        if (createBiotech$route == null || !heldItem.isEmpty()) return;
        var route = createBiotech$route;
        if (!createBiotech$authorized()) { createBiotech$retire("access_denied"); return; }
        var ready = createBiotech$ready(route.input(), inputs);
        if (!ready.equals("ready")) { createBiotech$setState(ready); return; }
        ready = createBiotech$ready(route.output(), outputs);
        if (!ready.equals("ready")) { createBiotech$setState(ready); return; }
        if (redstoneLocked) { createBiotech$setState("redstone_locked"); return; }
        var point = route.input().point();
        boolean stocked = false;
        for (int i = 0, slots = point.getSlotCount(createBiotech$arm()); i < slots; i++) {
            if (getDistributableAmount(point, i) > 0) {
                createBiotech$setState("moving");
                biocomputingSelectIndex(true, route.input().index());
                return;
            }
            if (!point.extract(createBiotech$arm(), i, true).isEmpty()) stocked = true;
        }
        createBiotech$setState(stocked ? "waiting_for_output" : "source_empty");
    }

    @Inject(method = "simulateInsertion", at = @At("HEAD"), cancellable = true)
    private void createBiotech$simulateSelectedOnly(ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        if (!createBiotech$managed()) return;
        var route = createBiotech$route;
        // Both managed callers checked authorization and both endpoints immediately before native simulation.
        cir.setReturnValue(route != null
            ? route.output().point().insert(createBiotech$arm(), stack, true) : stack);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/mechanicalArm/ArmInteractionPoint;keepAlive()V"))
    private void createBiotech$keepSelectedInputAlive(ArmInteractionPoint point) {
        if (createBiotech$arm().getLevel().isClientSide || !createBiotech$managed()
            || createBiotech$route != null && point == createBiotech$route.input().point()
                && createBiotech$ready(createBiotech$route.input(), inputs).equals("ready")) point.keepAlive();
    }

    @Inject(method = "collectItem", at = @At("HEAD"), cancellable = true)
    private void createBiotech$checkBeforeCollect(CallbackInfo ci) {
        if (!createBiotech$managed()) return;
        var route = createBiotech$route;
        if (route == null || !createBiotech$authorized()) {
            ci.cancel();
            createBiotech$resetToSearch(false);
            if (route != null) createBiotech$setState("access_denied");
            return;
        }
        String ready = redstoneLocked ? "redstone_locked" : createBiotech$ready(route.input(), inputs);
        if (ready.equals("ready")) ready = createBiotech$ready(route.output(), outputs);
        if (!ready.equals("ready") || chasedPointIndex != route.input().index()) {
            ci.cancel();
            createBiotech$setState(ready.equals("ready") ? "point_changed" : ready);
            createBiotech$resetToSearch(false);
        }
    }

    @Inject(method = "searchForDestination", at = @At("HEAD"), cancellable = true)
    private void createBiotech$chooseOutput(CallbackInfo ci) {
        if (!createBiotech$managed()) return;
        ci.cancel();
        var route = createBiotech$route;
        if (route == null || heldItem.isEmpty()) return;
        String ready = createBiotech$ready(route.output(), outputs);
        if (!ready.equals("ready")) { createBiotech$setState(ready); return; }
        var remainder = route.output().point().insert(createBiotech$arm(), heldItem.copy(), true);
        if (ItemStack.matches(heldItem, remainder)) { createBiotech$setState("waiting_for_output"); return; }
        createBiotech$setState("moving");
        biocomputingSelectIndex(false, route.output().index());
    }

    @Inject(method = "depositItem", at = @At("HEAD"), cancellable = true)
    private void createBiotech$checkBeforeDeposit(CallbackInfo ci) {
        if (!createBiotech$managed()) return;
        var route = createBiotech$route;
        String ready = route == null ? "recovery_required" : createBiotech$ready(route.output(), outputs);
        if (!ready.equals("ready") || chasedPointIndex != route.output().index()) {
            ci.cancel();
            createBiotech$setState(ready.equals("ready") ? "point_changed" : ready);
            createBiotech$resetToSearch(true);
        }
    }

    @Inject(method = "depositItem", at = @At("RETURN"))
    private void createBiotech$observeDeposit(CallbackInfo ci) {
        if (createBiotech$route == null) return;
        if (heldItem.isEmpty()) createBiotech$retire("transfer_completed");
        else createBiotech$setState("waiting_for_output");
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void createBiotech$saveRouteFence(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket || createBiotech$action == null) return;
        var state = new CompoundTag();
        state.putUUID("Id", createBiotech$action);
        state.putBoolean("Active", createBiotech$managed());
        state.putString("State", createBiotech$transfer);
        tag.put(CREATE_BIOTECH_ROUTE, state);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void createBiotech$restoreRouteFence(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        var state = tag.getCompound(CREATE_BIOTECH_ROUTE);
        createBiotech$route = null;
        createBiotech$routeStillAuthorized = null;
        createBiotech$action = state.hasUUID("Id") ? state.getUUID("Id") : null;
        createBiotech$recovery = createBiotech$action != null && state.getBoolean("Active") && !heldItem.isEmpty();
        createBiotech$transfer = createBiotech$action == null ? "idle" : createBiotech$recovery ? "recovery_required"
            : state.getBoolean("Active") ? "interrupted" : state.getString("State");
        if (state.getBoolean("Active")) {
            // The authorization does not survive reconstruction. Never replay the pickup or substitute the default output.
            phase = createBiotech$recovery ? ArmBlockEntity.Phase.SEARCH_OUTPUTS : ArmBlockEntity.Phase.SEARCH_INPUTS;
            chasedPointIndex = -1;
            chasedPointProgress = 0;
        }
    }

    @Unique private ArmBlockEntity createBiotech$arm() { return (ArmBlockEntity)(Object)this; }
    @Unique private boolean createBiotech$managed() { return createBiotech$route != null || createBiotech$recovery; }
    @Unique private boolean createBiotech$authorized() {
        try { return createBiotech$routeStillAuthorized != null && createBiotech$routeStillAuthorized.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }
    @Unique private String createBiotech$ready(ArmControl.Endpoint endpoint, List<ArmInteractionPoint> points) {
        if (!createBiotech$authorized()) return "access_denied";
        if (createBiotech$arm().isOverStressed()) return "overstressed";
        if (createBiotech$arm().getSpeed() == 0) return "no_drive";
        return ArmControl.endpointStatus(createBiotech$arm(), createBiotech$route, endpoint, points);
    }
    @Unique private void createBiotech$resetToSearch(boolean carrying) {
        var arm = createBiotech$arm();
        phase = carrying ? ArmBlockEntity.Phase.SEARCH_OUTPUTS : ArmBlockEntity.Phase.SEARCH_INPUTS;
        chasedPointIndex = -1;
        chasedPointProgress = 0;
        arm.setChanged();
        arm.sendData();
    }
    @Unique private void createBiotech$setState(String state) {
        if (state.equals(createBiotech$transfer)) return;
        createBiotech$transfer = state;
        createBiotech$arm().setChanged();
    }
    @Unique private void createBiotech$retire(String result) {
        createBiotech$route = null;
        createBiotech$routeStillAuthorized = null;
        createBiotech$setState(result);
    }
}
