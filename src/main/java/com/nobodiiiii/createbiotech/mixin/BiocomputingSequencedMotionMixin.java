package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.SequencedMotion;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SequencedGearshiftBlockEntity.class, remap = false)
public abstract class BiocomputingSequencedMotionMixin implements SequencedMotion.Access {
    @Shadow int currentInstruction;
    @Shadow public abstract boolean isIdle();
    @Shadow public abstract void run(int index);
    @Unique private static final String CREATE_BIOTECH_MOTION = "CreateBiotechMotion";
    @Unique private UUID createBiotech$motionId;
    @Unique private Instruction createBiotech$instruction;
    @Unique private int createBiotech$motionSpeed;
    @Unique private String createBiotech$phase = "idle";
    @Unique private boolean createBiotech$recoveredActive;

    @Override public boolean biocomputingStart(UUID id, Instruction instruction, int speed) {
        if (!isIdle() || createBiotech$instruction != null || createBiotech$recoveredActive) return false;
        createBiotech$motionId = id;
        createBiotech$instruction = instruction;
        createBiotech$motionSpeed = speed;
        createBiotech$phase = "running";
        try {
            run(0);
            return !isIdle();
        } finally {
            createBiotech$self().setChanged();
            if (isIdle()) createBiotech$retire("interrupted");
        }
    }

    @Override public UUID biocomputingMotionId() { return createBiotech$motionId; }
    @Override public String biocomputingPhase() {
        return !isIdle() && createBiotech$instruction == null && !createBiotech$recoveredActive ? "native_running" : createBiotech$phase;
    }

    @Inject(method = "getInstruction", at = @At("HEAD"), cancellable = true)
    private void createBiotech$selectTransientInstruction(int index, CallbackInfoReturnable<Instruction> cir) {
        if (createBiotech$instruction != null || createBiotech$recoveredActive)
            cir.setReturnValue(index == 0 ? createBiotech$instruction : null);
    }

    @Inject(method = "getModifier", at = @At("HEAD"), cancellable = true)
    private void createBiotech$selectTransientSpeed(CallbackInfoReturnable<Integer> cir) {
        if (createBiotech$recoveredActive) cir.setReturnValue(0);
        else if (createBiotech$instruction != null) cir.setReturnValue(currentInstruction == 0 ? createBiotech$motionSpeed : 0);
    }

    @Inject(method = "run", at = @At("RETURN"))
    private void createBiotech$observeRetirement(int index, CallbackInfo ci) {
        if (createBiotech$instruction != null && isIdle())
            createBiotech$retire(index == 1 ? "sequence_completed" : "interrupted");
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void createBiotech$recoverWithoutReplaying(CallbackInfo ci) {
        if (!createBiotech$recoveredActive || createBiotech$self().getLevel().isClientSide) return;
        // The recovered modifier is zero, so native run(-1) skips detaching.
        // Drop the saved downstream source and sequence context before resetting the instruction.
        createBiotech$self().detachKinetics();
        run(-1);
        createBiotech$recoveredActive = false;
        createBiotech$self().setChanged();
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void createBiotech$saveMotionReceipt(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket || createBiotech$motionId == null) return;
        var receipt = new CompoundTag();
        receipt.putUUID("Id", createBiotech$motionId);
        receipt.putString("Phase", createBiotech$recoveredActive ? "running" : createBiotech$phase);
        nbt.put(CREATE_BIOTECH_MOTION, receipt);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void createBiotech$loadMotionReceipt(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        var receipt = nbt.getCompound(CREATE_BIOTECH_MOTION);
        createBiotech$motionId = receipt.hasUUID("Id") ? receipt.getUUID("Id") : null;
        createBiotech$instruction = null;
        createBiotech$recoveredActive = "running".equals(receipt.getString("Phase"));
        createBiotech$phase = createBiotech$motionId == null ? "idle"
            : "sequence_completed".equals(receipt.getString("Phase")) ? "sequence_completed" : "interrupted";
    }

    @Unique private SequencedGearshiftBlockEntity createBiotech$self() { return (SequencedGearshiftBlockEntity)(Object)this; }
    @Unique private void createBiotech$retire(String phase) {
        createBiotech$instruction = null;
        createBiotech$phase = phase;
        createBiotech$self().setChanged();
    }
}
