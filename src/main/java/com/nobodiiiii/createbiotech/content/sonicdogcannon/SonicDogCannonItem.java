package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.List;
import java.util.function.Consumer;

import com.nobodiiiii.createbiotech.client.SonicDogCannonArmPose;
import com.nobodiiiii.createbiotech.client.SonicDogCannonItemRenderer;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import com.simibubi.create.foundation.item.render.CustomRenderedItems;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
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
	private static final double MIN_NORMAL_RANGE = 4.0d;
	private static final double MAX_NORMAL_RANGE = 16.0d;
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
		player.startUsingItem(hand);
		if (!level.isClientSide && !SonicDogCannonUpgrade.VOICE_PACK.isInstalled(stack))
			sendChargeSound(player, true);
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
		if (!hasVoicePack)
			sendChargeSound(player, false);

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

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
			hasVoicePack ? SoundEvents.WARDEN_SONIC_BOOM : SoundEvents.WOLF_AMBIENT,
			SoundSource.PLAYERS, 1.0f, 1.0f);
		player.awardStat(Stats.ITEM_USED.get(this));
	}

	private static void sendChargeSound(Player player, boolean playing) {
		SonicDogCannonChargeSoundPacket packet =
			new SonicDogCannonChargeSoundPacket(player.getId(), playing);
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
