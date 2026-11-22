package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.creeperblastchamber.CreeperBlastChamberBlockEntity;
import com.nobodiiiii.createbiotech.content.evokerenchantingchamber.EvokerEnchantingChamberBlock;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

@Mixin(GoggleOverlayRenderer.class)
public abstract class GoggleOverlayRendererMixin {
 @org.spongepowered.asm.mixin.injection.Redirect(method="renderOverlay",at=@At(value="INVOKE",target="Lcom/simibubi/create/api/equipment/goggles/IHaveGoggleInformation;addToGoggleTooltip(Ljava/util/List;Z)Z"))
 private static boolean createBiotech$advancedDiagnostics(com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation owner,java.util.List<net.minecraft.network.chat.Component> tooltip,boolean sneaking) {
  boolean normal=owner.addToGoggleTooltip(tooltip,sneaking);
  if(sneaking&&owner instanceof com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleInformation factory)
   com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleClient.append(factory,tooltip);
  return normal||!tooltip.isEmpty();
 }


	@Inject(
		method = "proxiedOverlayPosition(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
		at = @At("HEAD"), cancellable = true)
	private static void createBiotech$proxyCreeperBlastChamberStructure(Level level, BlockPos pos,
		CallbackInfoReturnable<BlockPos> cir) {
		BlockPos factoryPos = com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleProxy.resolve(level, pos);
		if (!factoryPos.equals(pos)) {
			cir.setReturnValue(factoryPos);
			return;
		}
		BlockPos controllerPos = CreeperBlastChamberBlockEntity.findGoggleInformationSource(level, pos);
		if (controllerPos != null) {
			cir.setReturnValue(controllerPos);
			return;
		}

		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof EvokerEnchantingChamberBlock
			&& state.getValue(EvokerEnchantingChamberBlock.HALF) == DoubleBlockHalf.UPPER) {
			cir.setReturnValue(pos.below());
		}
	}
}
