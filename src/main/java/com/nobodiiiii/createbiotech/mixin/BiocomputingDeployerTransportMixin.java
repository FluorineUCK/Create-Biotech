package com.nobodiiiii.createbiotech.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.DeployerControl;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ManagedDeployerWorldTicks;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.BeltDeployerCallbacks;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = BeltDeployerCallbacks.class, remap = false)
public abstract class BiocomputingDeployerTransportMixin {
    @WrapOperation(method = {"onItemReceived", "whenItemHeld"}, at = @At(value = "FIELD",
        target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;redstoneLocked:Z"))
    private static boolean createBiotech$allowOneNativeStart(DeployerBlockEntity deployer, Operation<Boolean> original,
            TransportedItemStack stack, TransportedItemStackHandlerBehaviour target, DeployerBlockEntity unused) {
        var access = (DeployerControl.Access)deployer;
        // This early gate only lets Create offer a recipe candidate and hold the real stack.
        return access.biocomputingSuppressWorld() ? !access.biocomputingMayQueueStart(target) : original.call(deployer);
    }

    @WrapOperation(method = {"onItemReceived", "whenItemHeld"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;start()V"))
    private static void createBiotech$captureNativeStart(DeployerBlockEntity deployer, Operation<Void> original,
            TransportedItemStack stack, TransportedItemStackHandlerBehaviour target, DeployerBlockEntity unused) {
        var access = (DeployerControl.Access)deployer;
        if (!access.biocomputingSuppressWorld()) { original.call(deployer); return; }
        ManagedDeployerWorldTicks.offer(deployer, target, stack, () -> {
            if (!access.biocomputingMayOpen(target) || deployer.getSpeed() == 0 || deployer.getRecipe(stack.stack) == null) return;
            access.biocomputingTransportStart(true);
            try {
                original.call(deployer);
                access.biocomputingCapture(stack, target);
            } finally {
                access.biocomputingTransportStart(false);
            }
        });
    }

    @WrapOperation(method = "whenItemHeld", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/deployer/BeltDeployerCallbacks;activate(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour;Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;)V"))
    private static void createBiotech$convertAtMostOnce(TransportedItemStack stack, TransportedItemStackHandlerBehaviour target,
            DeployerBlockEntity deployer, Recipe<?> recipe, Operation<Void> original) {
        var access = (DeployerControl.Access)deployer;
        if (!access.biocomputingSuppressWorld()) {
            original.call(stack, target, deployer, recipe);
            return;
        }
        ManagedDeployerWorldTicks.offer(deployer, target, stack, () -> {
            // Keep Create's exact conversion edge, but not authorization or inventory evidence sampled at it.
            var current = deployer.getSpeed() == 0 ? null : deployer.getRecipe(stack.stack);
            if (current == null || current.value() != recipe || !access.biocomputingMayConvert(stack, target)) return;
            original.call(stack, target, deployer, recipe);
            access.biocomputingConverted();
        });
    }
}
