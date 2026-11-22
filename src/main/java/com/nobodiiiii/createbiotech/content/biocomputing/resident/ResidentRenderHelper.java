package com.nobodiiiii.createbiotech.content.biocomputing.resident;

import java.lang.ref.WeakReference;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.foundation.render.SeatedModelPose;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.VillagerHeadModel;
import net.minecraft.client.model.ZombieVillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Client-only bounded resident display shared by Computer and Library renderers. */
public final class ResidentRenderHelper {
	private static final double ROOT_XZ = 0.5d;
	private static final float ZOMBIE_CROSSED_ARM_X_ROT = -0.75f;
	private static final float ZOMBIE_CROSSED_ARM_Z_ROT = 0.95f;
	private static final float VILLAGER_MODEL_SCALE = 0.9375f;

	private final EntityRenderDispatcher dispatcher;
	private final VillagerModel<Villager> villagerModel;
	private final VillagerModel<WanderingTrader> wanderingTraderModel;
	private final ZombieVillagerModel<ZombieVillager> zombieVillagerModel;
	private final ModelPart zombieVillagerRoot;
	private final VillagerProfessionLayer<Villager, VillagerModel<Villager>> villagerProfessionLayer;
	private final VillagerProfessionLayer<ZombieVillager, ZombieVillagerModel<ZombieVillager>>
		zombieVillagerProfessionLayer;
	private WeakReference<ClientLevel> displayLevel = new WeakReference<>(null);
	private WeakReference<Villager> displayVillager = new WeakReference<>(null);
	private WeakReference<WanderingTrader> displayWanderingTrader = new WeakReference<>(null);
	private WeakReference<ZombieVillager> displayZombieVillager = new WeakReference<>(null);

	public ResidentRenderHelper(BlockEntityRendererProvider.Context context) {
		dispatcher = context.getEntityRenderer();
		villagerModel = new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER));
		wanderingTraderModel = new VillagerModel<>(context.bakeLayer(ModelLayers.WANDERING_TRADER));
		zombieVillagerRoot = context.bakeLayer(ModelLayers.ZOMBIE_VILLAGER);
		zombieVillagerModel = new ZombieVillagerModel<>(zombieVillagerRoot);
		villagerProfessionLayer = new VillagerProfessionLayer<>(
			new ResidentModelParent<>(villagerModel, this::baseTexture),
			Minecraft.getInstance().getResourceManager(), "villager");
		zombieVillagerProfessionLayer = new VillagerProfessionLayer<>(
			new ResidentModelParent<>(zombieVillagerModel, this::baseTexture),
			Minecraft.getInstance().getResourceManager(), "zombie_villager");
	}

	public void render(BlockEntity blockEntity, ResidentProjection projection, Direction facing,
		ResidentRenderLayout layout, float partialTick, PoseStack poseStack, MultiBufferSource buffer) {
		render(blockEntity, projection, VillagerType.PLAINS, facing, layout, partialTick, poseStack, buffer);
	}

	public void render(BlockEntity blockEntity, ResidentProjection projection, VillagerType villagerType,
		Direction facing, ResidentRenderLayout layout, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer) {
		if (displayEntityClassFor(projection.entityType()) == Villager.class) {
			Villager villager = displayVillager(blockEntity, projection, villagerType);
			if (villager != null)
				renderModel(villager, villagerModel, villagerModel.root(), facing, layout, partialTick,
					hasVillagerProfessionLayerFor(projection.entityType())
						? this::renderVillagerProfessionLayer : null, poseStack, buffer);
			return;
		}
		if (displayEntityClassFor(projection.entityType()) == WanderingTrader.class) {
			WanderingTrader trader = displayWanderingTrader(blockEntity, projection);
			if (trader != null)
				renderModel(trader, wanderingTraderModel, wanderingTraderModel.root(), facing, layout,
					partialTick, null, poseStack, buffer);
			return;
		}
		ZombieVillager zombieVillager = displayZombieVillager(blockEntity, projection, villagerType);
		if (zombieVillager != null)
			renderModel(zombieVillager, zombieVillagerModel, zombieVillagerRoot, facing, layout,
				partialTick, hasVillagerProfessionLayerFor(projection.entityType())
					? this::renderZombieVillagerProfessionLayer : null, poseStack, buffer);
	}

	private <T extends LivingEntity> void renderModel(T entity, EntityModel<T> model, ModelPart root,
		Direction facing, ResidentRenderLayout layout, float partialTick,
		@Nullable ResidentPresentationLayer<T> presentationLayer, PoseStack poseStack, MultiBufferSource buffer) {
		prepareModel(model, root, entity, entity.tickCount + partialTick, layout.pose());
		poseStack.pushPose();
		assembleResidentModelTransform(poseStack, facing, layout, entity.getType());
		ResourceLocation texture = dispatcher.getRenderer(entity).getTextureLocation(entity);
		VertexConsumer consumer = buffer.getBuffer(model.renderType(texture));
		model.renderToBuffer(poseStack, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
			0xFFFFFFFF);
		if (presentationLayer != null)
			presentationLayer.render(poseStack, buffer, LightTexture.FULL_BRIGHT, entity,
				entity.tickCount + partialTick);
		poseStack.popPose();
	}

	/** Shared renderer transform for resident model vertices, kept callable by geometry acceptance tests. */
	static void assembleResidentModelTransform(PoseStack poseStack, Direction facing,
		ResidentRenderLayout layout, EntityType<?> entityType) {
		poseStack.translate(ROOT_XZ, layout.seatY(), ROOT_XZ);
		poseStack.mulPose(Axis.YP.rotationDegrees(ResidentProjection.yawFor(facing)));
		poseStack.scale(layout.scale(), layout.scale(), layout.scale());
		// Retain the fixed transform that LivingEntityRenderer applies before its model buffer draw.
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
		poseStack.scale(-1.0f, -1.0f, 1.0f);
		float modelScale = entityType == EntityType.ZOMBIE_VILLAGER ? 1.0f : VILLAGER_MODEL_SCALE;
		poseStack.scale(modelScale, modelScale, modelScale);
		poseStack.translate(0.0f, -1.501f, 0.0f);
	}

	private @Nullable Villager displayVillager(BlockEntity blockEntity, ResidentProjection projection,
		VillagerType villagerType) {
		ClientLevel level = displayLevel(blockEntity);
		if (level == null) return null;
		Villager villager = displayVillager.get();
		if (villager == null) {
			villager = new Villager(EntityType.VILLAGER, level);
			displayVillager = new WeakReference<>(villager);
		}
		VillagerType resolvedType = villagerType == null ? VillagerType.PLAINS : villagerType;
		villager.setVillagerData(new VillagerData(resolvedType, projection.profession(),
			projection.level()));
		prepareDisplayEntity(villager, projection);
		return villager;
	}

	private @Nullable WanderingTrader displayWanderingTrader(BlockEntity blockEntity,
		ResidentProjection projection) {
		ClientLevel level = displayLevel(blockEntity);
		if (level == null) return null;
		WanderingTrader trader = displayWanderingTrader.get();
		if (trader == null) {
			trader = new WanderingTrader(EntityType.WANDERING_TRADER, level);
			displayWanderingTrader = new WeakReference<>(trader);
		}
		prepareDisplayEntity(trader, projection);
		return trader;
	}

	private @Nullable ZombieVillager displayZombieVillager(BlockEntity blockEntity,
		ResidentProjection projection, VillagerType villagerType) {
		ClientLevel level = displayLevel(blockEntity);
		if (level == null) return null;
		ZombieVillager zombieVillager = displayZombieVillager.get();
		if (zombieVillager == null) {
			zombieVillager = new ZombieVillager(EntityType.ZOMBIE_VILLAGER, level);
			displayZombieVillager = new WeakReference<>(zombieVillager);
		}
		VillagerType resolvedType = villagerType == null ? VillagerType.PLAINS : villagerType;
		zombieVillager.setVillagerData(new VillagerData(resolvedType, projection.profession(),
			projection.level()));
		prepareDisplayEntity(zombieVillager, projection);
		return zombieVillager;
	}

	private @Nullable ClientLevel displayLevel(BlockEntity blockEntity) {
		ClientLevel level = blockEntity.getLevel() instanceof ClientLevel client
			? client : Minecraft.getInstance().level;
		if (level != displayLevel.get()) {
			displayLevel = new WeakReference<>(level);
			displayVillager = new WeakReference<>(null);
			displayWanderingTrader = new WeakReference<>(null);
			displayZombieVillager = new WeakReference<>(null);
		}
		return level;
	}

	private static void prepareDisplayEntity(Mob entity, ResidentProjection projection) {
		entity.setNoAi(true);
		entity.setSilent(true);
		entity.setPose(Pose.STANDING);
		entity.setCustomName(projection.customName() == null ? null
			: Component.literal(projection.customName()));
		entity.setYRot(0.0f);
		entity.setYBodyRot(0.0f);
		entity.yBodyRotO = 0.0f;
		entity.yHeadRot = 0.0f;
		entity.yHeadRotO = 0.0f;
	}

	/** The bounded entity families that can be rendered from a resident projection. */
	static Class<? extends LivingEntity> displayEntityClassFor(EntityType<?> entityType) {
		if (entityType == EntityType.VILLAGER) return Villager.class;
		if (entityType == EntityType.WANDERING_TRADER) return WanderingTrader.class;
		if (entityType == EntityType.ZOMBIE_VILLAGER) return ZombieVillager.class;
		throw new IllegalArgumentException("unsupported resident display entity");
	}

	static boolean hasVillagerProfessionLayerFor(EntityType<?> entityType) {
		return entityType == EntityType.VILLAGER || entityType == EntityType.ZOMBIE_VILLAGER;
	}

	private <T extends LivingEntity> ResourceLocation baseTexture(T entity) {
		return dispatcher.getRenderer(entity).getTextureLocation(entity);
	}

	private void renderVillagerProfessionLayer(PoseStack poseStack, MultiBufferSource buffer,
		int packedLight, Villager villager, float ageInTicks) {
		villagerProfessionLayer.render(poseStack, buffer, packedLight, villager, 0.0f, 0.0f,
			ageInTicks, 0.0f, 0.0f, 0.0f);
	}

	private void renderZombieVillagerProfessionLayer(PoseStack poseStack, MultiBufferSource buffer,
		int packedLight, ZombieVillager zombieVillager, float ageInTicks) {
		zombieVillagerProfessionLayer.render(poseStack, buffer, packedLight, zombieVillager, 0.0f,
			0.0f, ageInTicks, 0.0f, 0.0f, 0.0f);
	}

	private static <T extends LivingEntity> void prepareModel(EntityModel<T> model, ModelPart root,
		T entity, float ageInTicks, ResidentPose pose) {
		root.getAllParts().forEach(ModelPart::resetPose);
		model.setupAnim(entity, 0.0f, 0.0f, ageInTicks, 0.0f, 0.0f);
		if (pose != ResidentPose.SEATED) return;
		SeatedModelPose.apply(root);
		if (model instanceof ZombieVillagerModel<?>)
			applyZombieVillagerCrossedArmPose(root);
	}

	private static <T extends LivingEntity> void prepareSeatedModel(EntityModel<T> model, ModelPart root,
		T entity, float ageInTicks) {
		prepareModel(model, root, entity, ageInTicks, ResidentPose.SEATED);
	}

	/**
	 * Restores chest-crossing arm geometry after ZombieVillagerModel.setupAnim replaces it with
	 * {@code AnimationUtils.animateZombieArms}. Villager folded arms pitch down and forward by
	 * {@code -0.75}; separate zombie shoulder pivots additionally need inward Z rotations. With
	 * ModelPart's Z-Y-X transform, the negative-X right shoulder needs negative Z to reach the
	 * center, while the positive-X left shoulder needs positive Z.
	 */
	private static void applyZombieVillagerCrossedArmPose(ModelPart root) {
		ModelPart rightArm = root.getChild("right_arm");
		ModelPart leftArm = root.getChild("left_arm");
		rightArm.setRotation(ZOMBIE_CROSSED_ARM_X_ROT, 0.0f, -ZOMBIE_CROSSED_ARM_Z_ROT);
		leftArm.setRotation(ZOMBIE_CROSSED_ARM_X_ROT, 0.0f, ZOMBIE_CROSSED_ARM_Z_ROT);
	}

	@FunctionalInterface
	private interface ResidentPresentationLayer<T extends LivingEntity> {
		void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
			float ageInTicks);
	}

	private static final class ResidentModelParent<T extends LivingEntity & VillagerDataHolder,
		M extends EntityModel<T> & VillagerHeadModel> implements RenderLayerParent<T, M> {
		private final M model;
		private final Function<T, ResourceLocation> texture;

		private ResidentModelParent(M model, Function<T, ResourceLocation> texture) {
			this.model = model;
			this.texture = texture;
		}

		@Override public M getModel() { return model; }
		@Override public ResourceLocation getTextureLocation(T entity) { return texture.apply(entity); }
	}

	/** Per-shell visual geometry, deliberately separate from tunable gameplay configuration. */
	public record ResidentRenderLayout(double seatY, float scale, ResidentPose pose) {
		public ResidentRenderLayout(double seatY, float scale) {
			this(seatY, scale, ResidentPose.SEATED);
		}

		public ResidentRenderLayout {
			if (!Double.isFinite(seatY) || !Float.isFinite(scale) || scale <= 0.0f || pose == null)
				throw new IllegalArgumentException("invalid resident render layout");
		}
	}

	/** The model pose selected by an owning shell's render layout. */
	public enum ResidentPose { STANDING, SEATED }
}
