package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Client-only display villager renderer. The server never creates this entity. */
public class PatternStorageCoreRenderer implements BlockEntityRenderer<PatternStorageCoreBlockEntity> {
	private final EntityRenderDispatcher dispatcher;
	private Villager displayVillager;
	private ClientLevel displayLevel;

	public PatternStorageCoreRenderer(BlockEntityRendererProvider.Context context) {
		dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
	}

	@Override
	public void render(PatternStorageCoreBlockEntity be, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		if (be.getBlockState().getValue(PatternStorageCoreBlock.HALF) != DoubleBlockHalf.LOWER)
			return;
		PatternCoreClientState state = be.clientState();
		if (!state.renderLibrarian()) return;
		Villager villager = displayVillager(be, state);
		if (villager == null) return;
		poseStack.pushPose();
		poseStack.translate(0.5, 0.08, 0.5);
		poseStack.mulPose(Axis.YP.rotationDegrees(180));
		poseStack.scale(0.72f, 0.72f, 0.72f);
		dispatcher.setRenderShadow(false);
		RenderSystem.runAsFancy(() -> dispatcher.render(villager, 0, 0, 0, 0, partialTick,
			poseStack, buffer, LightTexture.FULL_BRIGHT));
		dispatcher.setRenderShadow(true);
		poseStack.popPose();
	}

	private Villager displayVillager(PatternStorageCoreBlockEntity be, PatternCoreClientState state) {
		ClientLevel level = be.getLevel() instanceof ClientLevel client ? client : Minecraft.getInstance().level;
		if (level == null) return null;
		if (displayVillager == null || displayLevel != level) {
			displayLevel = level;
			displayVillager = new Villager(EntityType.VILLAGER, level);
			displayVillager.setNoAi(true);
			displayVillager.setSilent(true);
		}
		VillagerType type = BuiltInRegistries.VILLAGER_TYPE.get(state.villagerType());
		displayVillager.setVillagerData(new VillagerData(type == null ? VillagerType.PLAINS : type,
			VillagerProfession.LIBRARIAN, state.librarianLevel()));
		if (state.customName() != null) displayVillager.setCustomName(Component.literal(state.customName()));
		else displayVillager.setCustomName(null);
		displayVillager.setYRot(0); displayVillager.setYBodyRot(0); displayVillager.yBodyRotO = 0;
		return displayVillager;
	}

	@Override
	public boolean shouldRenderOffScreen(PatternStorageCoreBlockEntity be) {
		return be.getBlockState().getValue(PatternStorageCoreBlock.HALF) == DoubleBlockHalf.LOWER;
	}

	@Override
	public AABB getRenderBoundingBox(PatternStorageCoreBlockEntity be) {
		return new AABB(be.getBlockPos()).expandTowards(0, 1, 0);
	}
}
