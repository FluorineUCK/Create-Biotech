package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.client.SonicDogCannonArmPose;
import com.nobodiiiii.createbiotech.client.SonicDogCannonItemRenderer;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicDogCannonChargeSoundPacket.Action;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBSoundEvents;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import com.simibubi.create.foundation.item.render.CustomRenderedItems;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import org.jetbrains.annotations.Nullable;

public class SonicDogCannonItem extends Item {

	public static final int MAX_DURABILITY = 100;
	public static final int FULL_CHARGE_TICKS = 40;
	private static final int VOICE_PACK_CHARGE_START_TICKS = 36;
	public static final double MIN_NORMAL_RANGE = 4.0d;
	public static final double MAX_NORMAL_RANGE = 16.0d;
	private static final double MIN_SHRIEK_RANGE = 8.0d;
	private static final double MAX_SHRIEK_RANGE = 24.0d;
	private static final double BEAM_HIT_RADIUS = 0.6d;

	public SonicDogCannonItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
		return slotChanged || newStack.getItem() != oldStack.getItem();
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown()
			&& AllItems.WRENCH.isIn(player.getOffhandItem())) {
			if (!SonicDogCannonUpgrade.hasInstalledUpgrade(stack))
				return InteractionResultHolder.fail(stack);

			if (!level.isClientSide) {
				SonicDogCannonUpgrade.removeLastInstalled(stack);
				AllSoundEvents.WRENCH_REMOVE.playOnServer(level, player.blockPosition(), 1.0f,
					level.getRandom().nextFloat() * 0.5f + 0.5f);
			}
			return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
		}

		player.startUsingItem(hand);
		if (!level.isClientSide) {
			Action action = SonicDogCannonUpgrade.VOICE_PACK.isInstalled(stack)
				? Action.VOICE_PACK_START
				: Action.DEFAULT_START;
			sendChargeSound(player, action);
		}
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return 72000;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		// The cannon supplies its own two-handed pose; vanilla's bow pose would override it.
		return UseAnim.NONE;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);

		boolean hasUpgrades = false;
		for (SonicDogCannonUpgrade upgrade : SonicDogCannonUpgrade.values()) {
			if (upgrade.isInstalled(stack)) {
				hasUpgrades = true;
				break;
			}
		}
		if (!hasUpgrades)
			return;

		tooltip.add(CommonComponents.EMPTY);
		tooltip.add(Component.translatable("item.create_biotech.sonic_dog_cannon.upgrades")
			.withStyle(ChatFormatting.GRAY));
		for (SonicDogCannonUpgrade upgrade : SonicDogCannonUpgrade.values()) {
			if (upgrade.isInstalled(stack))
				tooltip.add(CommonComponents.space().append(Component.translatable(upgrade.tooltipKey())
					.withStyle(ChatFormatting.AQUA)));
		}
	}

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
		if (level.isClientSide || !(entity instanceof Player player))
			return;

		int elapsed = getUseDuration(stack, entity) - remainingUseDuration;
		if (elapsed == VOICE_PACK_CHARGE_START_TICKS
			&& SonicDogCannonUpgrade.VOICE_PACK.isInstalled(stack))
			sendChargeSound(player, Action.VOICE_PACK_LOOP);

		if (elapsed <= 0 || elapsed % 20 != 0)
			return;

		if (!BacktankUtil.canAbsorbDamage(player, MAX_DURABILITY)) {
			EquipmentSlot slot = player.getUsedItemHand() == InteractionHand.MAIN_HAND
				? EquipmentSlot.MAINHAND
				: EquipmentSlot.OFFHAND;
			stack.hurtAndBreak(1, player, slot);
		}
	}

	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		if (!(entity instanceof Player player) || !(level instanceof ServerLevel serverLevel))
			return;
		boolean hasVoicePack = SonicDogCannonUpgrade.VOICE_PACK.isInstalled(stack);
		sendChargeSound(player, Action.STOP);

		int chargeTicks = Math.max(0, getUseDuration(stack, entity) - timeLeft);
		double charge = Math.min(chargeTicks, FULL_CHARGE_TICKS) / (double) FULL_CHARGE_TICKS;
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 origin = player.getEyePosition().add(direction.scale(0.5d));

		if (SonicDogCannonUpgrade.SHRIEK_SONIC_BOOM.isInstalled(stack)) {
			double range = MIN_SHRIEK_RANGE + (MAX_SHRIEK_RANGE - MIN_SHRIEK_RANGE) * charge;
			fireSonicBoom(serverLevel, player, origin, direction, range);
		} else {
			double range = MIN_NORMAL_RANGE + (MAX_NORMAL_RANGE - MIN_NORMAL_RANGE) * charge;
			SonicDogConeWave.fire(serverLevel, player, origin, direction, range);
		}

		SoundEvent fireSound = hasVoicePack
			? chargeTicks >= FULL_CHARGE_TICKS
				? CBSoundEvents.SONIC_DOG_CANNON_VOICE_PACK_FIRE_FULL.get()
				: CBSoundEvents.SONIC_DOG_CANNON_VOICE_PACK_FIRE_PARTIAL.get()
			: SoundEvents.WOLF_AMBIENT;
		level.playSound(null, player.getX(), player.getY(), player.getZ(), fireSound,
			SoundSource.PLAYERS, 1.0f, 1.0f);
		player.awardStat(Stats.ITEM_USED.get(this));
	}

	private static void sendChargeSound(Player player, Action action) {
		SonicDogCannonChargeSoundPacket packet =
			new SonicDogCannonChargeSoundPacket(player.getId(), action);
		CBPackets.sendToTrackingEntity(packet, player);
		if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
			CBPackets.sendToPlayer(packet, serverPlayer);
	}

	private static void fireSonicBoom(ServerLevel level, Player player, Vec3 origin, Vec3 direction, double range) {
		Vec3 end = origin.add(direction.scale(range));
		int particleCount = Math.max(1, (int) Math.ceil(range));
		for (int i = 1; i <= particleCount; i++) {
			double distance = Math.min(i, range);
			Vec3 particlePos = origin.add(direction.scale(distance));
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.SONIC_BOOM,
				particlePos.x, particlePos.y, particlePos.z, 1, 0.0d, 0.0d, 0.0d, 0.0d);
		}

		AABB searchBox = new AABB(origin, end).inflate(BEAM_HIT_RADIUS);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, searchBox,
			candidate -> candidate != player && candidate.isAlive() && !candidate.isSpectator())) {
			if (target.getBoundingBox().inflate(BEAM_HIT_RADIUS).clip(origin, end).isPresent())
				target.hurt(level.damageSources().sonicBoom(player), 1.0f);
		}
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return BacktankUtil.isBarVisible(stack, MAX_DURABILITY);
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return BacktankUtil.getBarWidth(stack, MAX_DURABILITY);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return BacktankUtil.getBarColor(stack, MAX_DURABILITY);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		CustomRenderedItems.register(this);
		consumer.accept(new IClientItemExtensions() {
			private final SonicDogCannonItemRenderer renderer = new SonicDogCannonItemRenderer();

			@Override
			public boolean applyForgeHandTransform(PoseStack poseStack, LocalPlayer player, HumanoidArm arm,
				ItemStack itemInHand, float partialTick, float equipProcess, float swingProcess) {
				if (!player.isUsingItem() || !player.getUseItem().is(SonicDogCannonItem.this))
					return false;

				HumanoidArm usedArm = player.getUsedItemHand() == InteractionHand.MAIN_HAND
					? player.getMainArm()
					: player.getMainArm().getOpposite();
				if (arm != usedArm)
					return false;

				// Vanilla resets the hand's equip height after every successful use(), which normally
				// lowers the item by 0.6 blocks before raising it again. Keep the neutral held transform
				// throughout charging while the internal equip height catches up in the background.
				float side = arm == HumanoidArm.RIGHT ? 1.0f : -1.0f;
				poseStack.translate(side * 0.56f, -0.52f, -0.72f);
				return true;
			}

			@Override
			@Nullable
			public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
				return SonicDogCannonArmPose.ARM_POSE.getValue();
			}

			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				return renderer;
			}
		});
	}
}
